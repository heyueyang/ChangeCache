package Cache;

import java.io.File;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.csvreader.CsvWriter;

import Util.CmdLineParser;
import Cache.CacheItem.CacheReason;
import Database.DatabaseManager;

public class Simulator {

    /**
     * Database prepared sql statements.
     */

    static final String findCommit = "select id, commit_date, is_bug_fix from scmlog "
        + "where repository_id =? and commit_date between ? and ? order by commit_date ASC";//查找scmlog表中出指定时间段内所有的commit记录，按照时间从前到后排序
    //select id, commit_date, is_bug_fix from scmlog where repository_id =1 order by commit_date ASC
    static final String findFile = "select actions.file_id, type from actions, content_loc "
        + "where actions.file_id=content_loc.file_id "
        + "and actions.commit_id=? and content_loc.commit_id=? "
        + "and actions.file_id in( "
        + "select file_id from file_types where type='code') order by loc DESC";//查找出指定commit_id下，所有的action记录，按照文件源码行数降序排序
//select count(distinct actions.file_id) from actions, content_loc where actions.file_id=content_loc.file_id and actions.commit_id=content_loc.commit_id and actions.commit_id in (select id from scmlog where repository_id =1) and actions.file_id in (select file_id from file_types where type='code');
    static final String findHunkId = "select id from hunks where file_id =? and commit_id =?";//查找hunk表中是否有指定file_id和commit_id的记录
    static final String findBugIntroCdate = "select commit_date from hunk_blames, scmlog "
        + "where hunk_id =? and hunk_blames.bug_commit_id=scmlog.id";//查找指定hunk_id，该hunk出现的commit发生的时间
    static final String findPid = "select id from repositories where id=?";//查找是否有指定id的工程
    static final String findFileCount = "select count(files.id) from files, file_types "
        + "where files.id = file_types.file_id and type = 'code' and repository_id=?";//查找指定工程中，所有的file_id的个数
    static final String findFileAliveCount = "select count(a.file_id) from "
    		+ "(select a1.file_id,scmlog.commit_date from actions a1,scmlog where a1.commit_id=scmlog.id and a1.type='A') a,file_types "
    		+ "where a.file_id=file_types.file_id and file_types.type='code' "
    		+ "and not exists (select a2.file_id from actions a2, scmlog s2 where a2.commit_id=s2.id and a2.file_id=a.file_id and s2.commit_date>a.commit_date and a2.type='D') ";
    //+ "and repository_id=?";
//(1)查找指定工程中，files表中所有file_id的个数
//select count(files.id) from files, file_types where files.id = file_types.file_id and type = 'code';
//(2)查找actions表中所有file_id的个数
//select count(distinct actions.file_id) from actions, file_types where actions.file_id=file_types.file_id and file_types.type='code';
//(3)查找指定工程中，actions表中所有有‘A’并且之后没有'D'的file_id
//select count(a.file_id) from (select a1.file_id,scmlog.commit_date from actions a1,scmlog where a1.commit_id=scmlog.id and a1.type='A') a,file_types where a.file_id=file_types.file_id and file_types.type='code' and not exists (select a2.file_id from actions a2, scmlog s2 where a2.commit_id=s2.id and a2.file_id=a.file_id and s2.commit_date>a.commit_date and a2.type='D');
//****************************************
//static final String findCurrentFileCount = "select count(distinct file_id) from actions" + "where commit_id<? and repository_id=?";
    private static PreparedStatement findCommitQuery;
    private static PreparedStatement findFileQuery;
    private static PreparedStatement findHunkIdQuery;
    static PreparedStatement findBugIntroCdateQuery;
    static PreparedStatement findPidQuery;
    static PreparedStatement findFileCountQuery;
    static PreparedStatement findFileAliveCountQuery;
    //static PreparedStatement findCurrentFileCountQuery;
    static int CountChanged;
    static String[] projects = {"voldemort","ant","camel","eclipse", "itextpdf","jEdit","liferay","struts","tomcat"};//*
    static String result_dir = "Results/Result_parameter/";//*

    /**
     * From the actions table. See the cvsanaly manual
     * (http://gsyc.es/~carlosgc/files/cvsanaly.pdf), pg 11
     */
    public enum FileType {
        A, M, D, V, C, R
    }

    /**
     * Member fields
     */
    final int blocksize; // number of co-change files to import
    final int prefetchsize; // number of (new or modified but not buggy) files
    // to import
    final int cachesize; // size of cache
    final int pid; // project (repository) id
    final boolean saveToFile; // whether there should be csv output
    final CacheReplacement.Policy cacheRep; // cache replacement policy
    final Cache cache; // the cache
    static Connection conn = null;//DatabaseManager.getConnection(); // for database

    int hit;
    int miss;
    private int commits;
    private Set<Integer> currentFileSet = new HashSet<Integer>();
    private static int total_files_alive;
    private static int total_files;

    // For output
    // XXX separate class to manage output
    String outputDate;
    int outputSpacing = 3; // output the hit rate every 3 months
    int month = outputSpacing;
    CsvWriter csvWriter;
    int fileCount; // XXX where is this set? why static?
    String filename;

    public Simulator(int bsize, int psize, int csize, int projid,
            CacheReplacement.Policy rep, String start, String end, Boolean save) {

        pid = projid;        
        int onepercent = getPercentOfFiles(pid);

        if (bsize == -1)
            blocksize = onepercent*5;//默认的blocksize为总文件数量的5%，10%
        else
            blocksize = bsize;
        if (csize == -1)
            cachesize = onepercent*10; //默认的cachesize为总文件数量的10%
        else
            cachesize = csize;
        if (psize == -1)
            prefetchsize = onepercent*1;//默认的prefetchsize为总文件数量的1%，5%
        else
            prefetchsize = psize;

        cacheRep = rep;

        start = findFirstDate(start, pid);//获取最早日期
        end = findLastDate(end, pid);//获取最晚日期


        cache = new Cache(cachesize, new CacheReplacement(rep), start, end,
                projid);//初始化cache
        outputDate = cache.startDate;

        hit = 0;
        miss = 0;
        this.saveToFile = save;
        //如果结果要输出到文件，设置文件路径以及注释的等内容
        if (saveToFile == true) {
            filename = pid + "_" + cachesize + "_" + blocksize + "_"
            + prefetchsize + "_" + cacheRep;
            csvWriter = new CsvWriter(result_dir + filename + "_hitrate.csv");
            csvWriter.setComment('#');
            try {
                csvWriter.writeComment("hitrate for every 3 months, "
                        + "used to describe the variation of hit rate with time");
                /*csvWriter.writeComment("project: " + pid + ", cachesize: "
                        + cachesize + ", blocksize: " + cachesize
                        + ", prefetchsize: " + prefetchsize
                        + ", cache replacement policy: " + cacheRep);*/
                csvWriter.writeComment("project: " + pid +  ", total_files: " + total_files + ", total_files_alive: " + total_files_alive  + ", cacheTableSize: " + cache.getCacheSizeEver() + ", cachesize: " + cachesize
                        + ", blocksize: " + blocksize + ", prefetchsize: "
                        + prefetchsize + ", cache replacement policy: " + cacheRep);
                csvWriter.write("Month");
                //csvWriter.write("Range");
                csvWriter.write("HitRate");
                csvWriter.write("NumCommits");
                csvWriter.write("NumFiles");
                csvWriter.endRecord();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            findFileQuery = conn.prepareStatement(findFile);
            findCommitQuery = conn.prepareStatement(findCommit);
            findHunkIdQuery = conn.prepareStatement(findHunkId);
            findBugIntroCdateQuery = conn.prepareStatement(findBugIntroCdate);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static int getFileCount(int projid) {
        int ret = 0;
        try {
        	
        	findFileCountQuery = conn.prepareStatement(findFileCount); 
            findFileCountQuery.setInt(1, projid);
            ret = Util.Database.getIntResult(findFileCountQuery);
            total_files = ret;
            findFileAliveCountQuery = conn.prepareStatement(findFileAliveCount); 
            //findFileCountQuery.setInt(1, projid);
            ret = Util.Database.getIntResult(findFileAliveCountQuery);
            total_files_alive = ret; 
            //System.out.println("total_files : " + total_files);
            //System.out.println("total_files_alive : " + total_files_alive);
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
        return ret;
    }
    

    /**
     * Prints out the command line options
     */
    private static void printUsage() {
        System.err
        .println("Example Usage: FixCache -b=10000 -c=500 -f=600 -r=\"LRU\" -p=1");
        System.err.println("Example Usage: FixCache --blksize=10000 "
                + "--csize=500 --pfsize=600 --cacherep=\"LRU\" --pid=1");
        System.err.println("-p/--pid option is required");
    }

    /**
     * Loads an entity containing a bug into the cache.
     * 
     * @param fileId
     * @param cid
     *            -- commit id
     * @param commitDate
     *            -- commit date
     * @param intro_cdate
     *            -- bug introducing commit date
     * @throws Exception 
     */
    // XXX move hit and miss to the cache?
    // could add if (reas == BugEntity) logic to add() code
    public void loadBuggyEntity(int fileId, int cid, String commitDate, String intro_cdate) throws Exception {
    	//System.out.println(fileId  + "->" + cid + ":" + commitDate + "---" + intro_cdate);
        if (cache.contains(fileId))//计算是否命中
            hit++; 
        else
            miss++;

        cache.add(fileId, cid, commitDate, CacheItem.CacheReason.BugEntity);//时间局部性，将自身加入cache
        CountChanged++;
        // add the co-changed files as well
        ArrayList<Integer> cochanges = CoChange.getCoChangeFileList(fileId,
                cache.startDate, intro_cdate, blocksize);
        cache.add(cochanges, cid, commitDate, CacheItem.CacheReason.CoChange);//空间局部性，将co-change的记录加入cache
    }
    
    public void loadChangeEntity(int fileId, int cid, String commitDate) throws Exception {

        if (cache.contains(fileId))//计算是否命中
            hit++; 
        else
            miss++;

        cache.add(fileId, cid, commitDate, CacheItem.CacheReason.BugEntity);//时间局部性，将自身加入cache

        // add the co-changed files as well
        ArrayList<Integer> cochanges = CoChange.getCoChangeFileList(fileId,
                cache.startDate, commitDate, blocksize);
        //for(Integer file_id : cochanges){
        //	System.out.print(file_id+"\t");
        //}
        //System.out.println();
        cache.add(cochanges, cid, commitDate, CacheItem.CacheReason.CoChange);//空间局部性，将co-change的记录加入cache
        //weka.associations.Apriori ap = new weka.associations.Apriori();
        //ap.buildAssociations(new Instances());
        //ap.getCapabilities();
    }

    /**
     * The main simulate loop. This loop processes all revisions starting at
     * cache.startDate
     * 
     */
    public void simulate() {

        final ResultSet allCommits;
        int cid;// means commit_id in actions
        String cdate = null;

        boolean isBugFix;
        int file_id;
        FileType type;
        int numprefetch = 0;

        // iterate over the selection
        try {
            findCommitQuery.setInt(1, pid);
            findCommitQuery.setString(2, cache.startDate);
            findCommitQuery.setString(3, cache.endDate);

            // returns all commits to pid after cache.startDate
            allCommits = findCommitQuery.executeQuery();//获取scmlog表中，该工程的所有commit记录
            //遍历所有commit记录
            while (allCommits.next()) {
                commits++;
                cid = allCommits.getInt(1);
                cdate = allCommits.getString(2);
                isBugFix = allCommits.getBoolean(3);

                findFileQuery.setInt(1, cid);
                findFileQuery.setInt(2, cid);

                final ResultSet files = findFileQuery.executeQuery();              
                // loop through those file ids
                while (files.next()) {//遍历该commit_id涉及的所有action
                	
                    file_id = files.getInt(1);
                    currentFileSet.add(file_id);
                    type = FileType.valueOf(files.getString(2));
                    numprefetch = processOneFile(cid, cdate, isBugFix, file_id,
                            type, numprefetch);
                }
                numprefetch = 0;
                if (saveToFile == true) {
                    if (Util.Dates.getMonthDuration(outputDate, cdate) > outputSpacing
                            || cdate.equals(cache.endDate)) {
                        outputHitRate(cdate);
                    }
                }                   
            }      
        } catch (Exception e) {
            System.out.println(e);
            e.printStackTrace();
        }
    }

    private void outputHitRate(String cdate) {
        // XXX what if commits are more than 3 months apart?
        //final String formerOutputDate = outputDate;

        if (!cdate.equals(cache.endDate)) {
            outputDate = Util.Dates.monthsLater(outputDate, outputSpacing);
        } else {
            outputDate = cdate; // = cache.endDate
        }

        try {
            csvWriter.write(Integer.toString(month));
            //csvWriter.write(Util.Dates.getRange(formerOutputDate, outputDate));
            csvWriter.write(Double.toString(getHitRate()));
            csvWriter.write(Integer.toString(getCommitCount()));
            csvWriter.write(Integer.toString(currentFileSet.size()));
            csvWriter.endRecord();
        } catch (IOException e) {
            e.printStackTrace();
        }
        month += outputSpacing;
        if (Util.Dates.getMonthDuration(outputDate, cdate) > outputSpacing){
            outputHitRate(cdate);
        }
    }

    private int processOneFile(int cid, String cdate, boolean isBugFix,
            int file_id, FileType type, int numprefetch) throws Exception {
    	//System.out.print(cid + " : " + file_id+"*********");
        switch (type) {
        case V:
            break;
        case R:
        case C:
        case A:
            if (numprefetch < prefetchsize) {//添加局部性，如果该文件是新添加的，将该文件预取到内存中
                numprefetch++;//预加载计数器加1
                cache.add(file_id, cid, cdate, CacheItem.CacheReason.NewEntity);//将该条记录加入cache
            }
            break;
        case D:
            if(cache.contains(file_id)){
                this.cache.remove(file_id, cdate);//如果文件被删除，要从cache中移除
            }
            break;
        case M: // modified
        	this.loadBuggyEntity(file_id, cid, cdate, cdate);//向cache中加载，引入日期的相应的记录
        }
        return numprefetch;
    }
    

    /**
     * Gets the current hit rate of the cache
     * 
     * @return hit rate of the cache
     */
    public double getHitRate() {
        double hitrate = (double) hit / (hit + miss);
        return (double) Math.round(hitrate * 10000) / 100;
    }

    /**
     * Database accessors
     */

    /**
     * Fills cache with pre-fetch size number of top-LOC files from initial
     * commit. Only called once per simulation // implicit input: initial commit
     * ID // implicit input: LOC for every file in initial commit ID // implicit
     * input: pre-fetch size
     */
    public void initialPreLoad() {
    	//按照初始时刻文件源码的行数降序排序，将文件行数最大的那些加载进内存
        final String findInitialPreload = "select content_loc.file_id, content_loc.commit_id "
            + "from content_loc, scmlog, actions, file_types "
            + "where repository_id=? and content_loc.commit_id = scmlog.id and commit_date =? "
            + "and content_loc.file_id=actions.file_id "
            + "and content_loc.commit_id=actions.commit_id and actions.type!='D' "
            + "and file_types.file_id=content_loc.file_id and file_types.type='code' order by loc DESC";
        final PreparedStatement findInitialPreloadQuery;
        ResultSet r = null;
        int fileId = 0;
        int commitId = 0;

        try {
            findInitialPreloadQuery = conn.prepareStatement(findInitialPreload);
            findInitialPreloadQuery.setInt(1, pid);
            findInitialPreloadQuery.setString(2, cache.startDate);
            r = findInitialPreloadQuery.executeQuery();
        } catch (SQLException e1) {
            e1.printStackTrace();
        }
        String initialLoadQueryStr = "select content_loc.file_id, content_loc.commit_id "
                + "from content_loc, scmlog, actions, file_types "
                + "where repository_id=" + pid 
                + " and content_loc.commit_id = scmlog.id "
                + " and content_loc.file_id=actions.file_id "
                + "and content_loc.commit_id=actions.commit_id and actions.type!='D' "
                + "and file_types.file_id=content_loc.file_id and file_types.type='code' order by scmlog.commit_date limit " + prefetchsize;
        //System.out.println(initialLoadQueryStr);
        for (int size = 0; size < prefetchsize; size++) {
            try {
                if (r.next()) {
                    fileId = r.getInt(1);
                    commitId = r.getInt(2);
                    cache.add(fileId, commitId, cache.startDate,
                            CacheItem.CacheReason.Prefetch);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Finds the first date after the startDate with repository entries. Only
     * called once per simulation.
     * 
     * @return The date for the prefetch.
     */
    private static String findFirstDate(String start, int pid) {
        String findFirstDate = "";
        final PreparedStatement findFirstDateQuery;
        String firstDate = "";
        try {
            if (start == null) {
                findFirstDate = "select min(commit_date) from scmlog where repository_id=?";
                findFirstDateQuery = conn.prepareStatement(findFirstDate);
                findFirstDateQuery.setInt(1, pid);
            } else {
                findFirstDate = "select min(commit_date) from scmlog where repository_id=? and commit_date >=?";
                findFirstDateQuery = conn.prepareStatement(findFirstDate);
                findFirstDateQuery.setInt(1, pid);
                findFirstDateQuery.setString(2, start);
            }
            firstDate = Util.Database.getStringResult(findFirstDateQuery);
            if (firstDate == null) {
                System.out.println("Can not find any commit after "
                        + start);
                System.exit(2);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return firstDate;
    }

    /**
     * Finds the last date before the endDate with repository entries. Only
     * called once per simulation.
     * 
     * @return The date for the the simulator.
     */
    private static String findLastDate(String end, int pid) {
        String findLastDate = null;
        final PreparedStatement findLastDateQuery;
        String lastDate = null;
        try {
            if (end == null) {
                findLastDate = "select max(commit_date) from scmlog where repository_id=?";
                findLastDateQuery = conn.prepareStatement(findLastDate);
                findLastDateQuery.setInt(1, pid);
            } else {
                findLastDate = "select max(commit_date) from scmlog where repository_id=? and commit_date <=?";
                findLastDateQuery = conn.prepareStatement(findLastDate);
                findLastDateQuery.setInt(1, pid);
                findLastDateQuery.setString(2, end);
            }
            lastDate = Util.Database.getStringResult(findLastDateQuery);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if (lastDate == null) {
            System.out.println("Can not find any commit before "
                    + end);
            System.exit(2);
        }
        return lastDate;
    }

    /**
     * use the fileId and commitId to get a list of changed hunks from the hunk
     * table. for each changed hunk, get the blamedHunk from the hunk_blame
     * table; get the commit id associated with this blamed hunk take the
     * maximum (in terms of date?) commit id and return it
     * */

    public String getBugIntroCdate(int fileId, int commitId) {

        // XXX optimize this code?
        String bugIntroCdate = "";
        int hunkId;
        ResultSet r = null;
        ResultSet r1 = null;
        try {
            findHunkIdQuery.setInt(1, fileId);
            findHunkIdQuery.setInt(2, commitId);
            r = findHunkIdQuery.executeQuery();
            while (r.next()) {
                hunkId = r.getInt(1);

                findBugIntroCdateQuery.setInt(1, hunkId);
                r1 = findBugIntroCdateQuery.executeQuery();
                while (r1.next()) {
                    if (r1.getString(1).compareTo(bugIntroCdate) > 0) {
                        bugIntroCdate = r1.getString(1);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println(e);
            System.exit(0);
        }

        return bugIntroCdate;
    }

    /**
     * Closes the database connection
     */
    private void close() {
    	System.out.println("db closed!");
        DatabaseManager.close();
    }

    /**
     * For Debugging
     */

    public int getHit() {
        return hit;
    }

    public int getMiss() {
        return miss;
    }

    public Cache getCache() {
        return cache;
    }

    public void add(int eid, int cid, String cdate, CacheReason reas) {
        cache.add(eid, cid, cdate, reas);
    }

    // XXX move to a different part of the file
    public static void checkParameter(String start, String end, int pid) {
        if (start != null && end != null) {
            if (start.compareTo(end) > 0) {
                System.err
                .println("Error:Start date must be earlier than end date");
                printUsage();
                System.exit(2);
            }
        }
        try {
            findPidQuery = conn.prepareStatement(findPid);
            findPidQuery.setInt(1, pid);
            if (Util.Database.getIntResult(findPidQuery) == -1) {
                System.out.println("There is no project whose id is " + pid);
                System.exit(2);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    public static void main(String args[]) {

        /**
         * Command line parsing
         */
        CmdLineParser parser = new CmdLineParser();
        CmdLineParser.Option blksz_opt = parser.addIntegerOption('b', "blksize");
        CmdLineParser.Option csz_opt = parser.addIntegerOption('c', "csize");
        CmdLineParser.Option pfsz_opt = parser.addIntegerOption('f', "pfsize");
        CmdLineParser.Option crp_opt = parser.addStringOption('r', "cacherep");
        CmdLineParser.Option pid_opt = parser.addIntegerOption('p', "pid");
        CmdLineParser.Option sd_opt = parser.addStringOption('s', "start");
        CmdLineParser.Option ed_opt = parser.addStringOption('e', "end");
        CmdLineParser.Option save_opt = parser.addBooleanOption('o',"save");
        CmdLineParser.Option tune_opt = parser.addBooleanOption('u', "tune");
        CmdLineParser.Option name_opt = parser.addStringOption('n', "name");
        CmdLineParser.Option val_opt = parser.addBooleanOption('v', "validate");
        CmdLineParser.Option path_opt = parser.addStringOption('d', "path");
        // CmdLineParser.Option sCId_opt = parser.addIntegerOption('s',"start");
        // CmdLineParser.Option eCId_opt = parser.addIntegerOption('e',"end");
        try {
            parser.parse(args);
        } catch (CmdLineParser.OptionException e) {
            System.err.println(e.getMessage());
            printUsage();
            System.exit(2);
        }
        //设置参数
        Integer blksz = (Integer) parser.getOptionValue(blksz_opt, -1);
        Integer csz = (Integer) parser.getOptionValue(csz_opt, -1);
        Integer pfsz = (Integer) parser.getOptionValue(pfsz_opt, -1);
        String crp_string = (String) parser.getOptionValue(crp_opt,
                CacheReplacement.REPDEFAULT.toString());
        Integer pid = (Integer) parser.getOptionValue(pid_opt);
        String start = (String) parser.getOptionValue(sd_opt, null);
        String end = (String) parser.getOptionValue(ed_opt, null);
        Boolean saveToFile = (Boolean) parser.getOptionValue(save_opt, false);
        Boolean tune = (Boolean)parser.getOptionValue(tune_opt, false);
        Boolean validate = (Boolean)parser.getOptionValue(val_opt, false);
        String name = (String) parser.getOptionValue(name_opt, null);
        String outPath = (String) parser.getOptionValue(path_opt, null);
        
        CacheReplacement.Policy crp;
        try {
            crp = CacheReplacement.Policy.valueOf(crp_string);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println("Must specify a valid cache replacement policy");
            printUsage();
            crp = CacheReplacement.REPDEFAULT;
        }
        if (pid == null) {
            System.err.println("Error: must specify a Project Id");
            printUsage();
            System.exit(2);
        }

        /**
         * Create a new simulator and run simulation.
         */
        Simulator sim;
        //for(int i = 0 ; i < projects.length; i++){
        	//System.out.println("==================================" + projects[i] + "==================================");
	        if(outPath != null){
	        	result_dir = outPath + name + "7/";
	        }else{
	        	result_dir += name + "7/";// + projects[i] + "3/";		//*
	        }
        	
        	if(!new File(result_dir).exists()){					//*
            	new File(result_dir).mkdirs();		//*
        	}else{
        		System.out.println("Result path : " + result_dir + " already exists， Exit！");
        		System.exit(0);
        		//return;
        	}
        	
        	conn = DatabaseManager.getConnection(name); 	//*	projects[i]
        	checkParameter(start, end, pid);
	        //调整参数
	        if(validate){
	            System.out.println("parameters analyze...");
	            sim = validate(pid);
	            System.out.println(".... finished analyze!");
	        }else if(tune)
	        {
	            System.out.println("tuning...");
	            sim = tune(pid);
	            System.out.println(".... finished tuning!");
	            System.out.println("highest hitrate:"+sim.getHitRate());
	        }
	        else
	        {
	        				
	            sim = new Simulator(blksz, pfsz, csz, pid, crp, start, end, saveToFile);									
	            sim.initialPreLoad();//预加载
	            sim.simulate();//进行仿真
	            //保存结果
	            if(sim.saveToFile==true)
	            {
	                sim.csvWriter.close();
	                sim.outputFileDist();
	                sim.outputFileChangeReason();
	            }
	        }
	
	        // should always happen
	        sim.close();
	        printSummary(sim);
        //}
    }


    private static void printSummary(Simulator sim) {
        System.out.println("Simulator specs:");
        System.out.print("Project....");
        System.out.println(sim.pid);
        System.out.print("Cache size....");
        System.out.println(sim.cachesize);
        System.out.print("Blk size....");
        System.out.println(sim.blocksize);
        System.out.print("Prefetch size....");
        System.out.println(sim.prefetchsize);
        System.out.print("Start date....");
        System.out.println(sim.cache.startDate);
        System.out.print("End date....");
        System.out.println(sim.cache.endDate);
        System.out.print("Cache Replacement Policy ....");
        System.out.println(sim.cacheRep.toString());
        System.out.print("saving to file....");
        System.out.println(sim.saveToFile);


        System.out.println("\nResults:");
        System.out.print("Hit rate...");
        System.out.println(sim.getHitRate());

        System.out.print("Num commits processed...");
        System.out.println(sim.getCommitCount());

        System.out.print("Num bug fixes...");
        System.out.println(sim.getHit() + sim.getMiss());
        
        System.out.print("Num changed actions...");
        System.out.println(CountChanged);
    }


    private static Simulator tune(int pid)
    {
        Simulator maxsim = null;
        double maxhitrate = 0;

        int onepercent = getPercentOfFiles(pid);
        System.out.println("One percent of files: " + onepercent);
        int blksz = onepercent;
        int pfsz = onepercent;
        int csz = 0;
        final int UPPER = 10*onepercent;
        CacheReplacement.Policy crp = CacheReplacement.REPDEFAULT;
        
        for(blksz=onepercent;blksz<UPPER;blksz+=onepercent*2){
            for(pfsz=onepercent;pfsz<UPPER;pfsz+=onepercent*2){
                final Simulator sim = new Simulator(blksz, pfsz,-1, pid, crp, null, null, false);
                sim.initialPreLoad();
                sim.simulate();
                System.out.println(sim.getHitRate());
                if(sim.getHitRate()>maxhitrate)
                {
                    maxhitrate = sim.getHitRate();
                    maxsim = sim;
                }

            }
        }
        
        System.out.println("Trying out different cache replacment policies...");
        for(CacheReplacement.Policy crtst :CacheReplacement.Policy.values()){
            final Simulator sim = 
                new Simulator(maxsim.blocksize, maxsim.prefetchsize,
                        -1, pid, crtst, null, null, false);
            sim.initialPreLoad();
            sim.simulate();
            System.out.println(sim.getHitRate());
            if(sim.getHitRate()>maxhitrate)
            {
                maxhitrate = sim.getHitRate();
                maxsim = sim;
            }
        }
        
        /*for(int i = 0; i < 10; i++){
        	csz = csz + UPPER;
            final Simulator sim = new Simulator(-1, -1,csz, pid, crp, null, null, false);
            sim.initialPreLoad();
            sim.simulate();
            System.out.println((i+1) + "（"+csz+ "): " + sim.getHitRate());
            if(sim.getHitRate()>maxhitrate)
            {
                maxhitrate = sim.getHitRate();
                maxsim = sim;
            }

        }*/
        maxsim.close();
        return maxsim;
    }
    
    private static Simulator validate(int pid)
    {
        Simulator maxsim = null;
        double maxhitrate = 0;

        int onepercent = getPercentOfFiles(pid);
        System.out.println("One percent of files: " + onepercent);
        int blksz = onepercent;
        int pfsz = onepercent;
        int csz = 0;
        final int UPPER = 10*onepercent;
        CacheReplacement.Policy crp = CacheReplacement.REPDEFAULT;
        int maxblksz = Integer.MIN_VALUE;
        int maxpfsz = Integer.MIN_VALUE;
        System.out.println("Trying out different block size...");
        ArrayList<Double[]> paras = new ArrayList<Double[]>();
        for(blksz=onepercent;blksz<UPPER;blksz+=onepercent){
        	
                final Simulator sim = new Simulator(blksz, pfsz,-1, pid, crp, null, null, false);
                sim.initialPreLoad();
                sim.simulate();
                System.out.println(sim.cachesize + "," + sim.blocksize + "," + sim.prefetchsize + " : " + sim.getHitRate());
                paras.add(new Double[]{(double) sim.cachesize, (double) sim.blocksize ,(double) sim.prefetchsize ,sim.getHitRate()});
                if(sim.getHitRate()>maxhitrate)
                {
                    maxhitrate = sim.getHitRate();
                    maxsim = sim;
                    maxblksz = blksz;
                }

        }
    	CsvWriter csvTempWriter = new CsvWriter(result_dir + "block" + ".csv");
        outputParameterTune(csvTempWriter,paras);
        System.out.println("Trying out different prefetch size...");
        blksz = maxblksz;
        paras = new ArrayList<Double[]>();
        for(pfsz=onepercent;pfsz<UPPER;pfsz+=onepercent){
            final Simulator sim = new Simulator(blksz, pfsz,-1, pid, crp, null, null, false);
            sim.initialPreLoad();
            sim.simulate();
            System.out.println(sim.cachesize + "," + sim.blocksize + "," + sim.prefetchsize + " : " + sim.getHitRate());
            paras.add(new Double[]{(double) sim.cachesize, (double) sim.blocksize ,(double) sim.prefetchsize ,sim.getHitRate()});
            if(sim.getHitRate()>maxhitrate)
            {
                maxhitrate = sim.getHitRate();
                maxsim = sim;
                maxpfsz = pfsz;
            }
        }
        paras.add(new Double[]{(double) 0, (double) maxblksz ,(double) maxpfsz ,maxhitrate});
        csvTempWriter = new CsvWriter(result_dir + "prefetch" + ".csv");
        outputParameterTune(csvTempWriter,paras);
        
        /*for(int i = 0; i < 10; i++){
        	csz = csz + UPPER;
            final Simulator sim = new Simulator(-1, -1,csz, pid, crp, null, null, false);
            sim.initialPreLoad();
            sim.simulate();
            System.out.println((i+1) + "（"+csz+ "): " + sim.getHitRate());
            if(sim.getHitRate()>maxhitrate)
            {
                maxhitrate = sim.getHitRate();
                maxsim = sim;
            }

        }*/
        maxsim.close();
        return maxsim;
    }

    private static int getPercentOfFiles(int pid) {
        int ret =  (int) Math.round(getFileCount(pid)*0.01);
        if (ret == 0)
            return 1;
        else
            return ret;
    }

    public void outputFileDist() {
    	//输出最终缓存中的文件列表
        csvWriter = new CsvWriter(result_dir + filename + "_filedist.csv");
        csvWriter.setComment('#');
        try {
            // csvWriter.write("# number of hit, misses and time stayed in Cache for every file");
            csvWriter.writeComment("number of hit, misses and time stayed in Cache for every file");
            csvWriter.writeComment("project: " + pid +  ", total_files: " + total_files + ", total_files_alive: " + total_files_alive  + ", cacheTableSize: " + cache.getCacheSizeEver() + ", cachesize: " + cachesize
                    + ", blocksize: " + blocksize + ", prefetchsize: "
                    + prefetchsize + ", cache replacement policy: " + cacheRep);
            csvWriter.write("file_id");
            csvWriter.write("loc");
            csvWriter.write("num_load");
            csvWriter.write("num_hits");
            csvWriter.write("num_misses");
            csvWriter.write("duration");
            csvWriter.write("reason");
            csvWriter.write("is_in_cache");
            csvWriter.write("HitPrefetch");
            csvWriter.write("HitCoChange");
            csvWriter.write("HitChangeEntity");
            csvWriter.write("HitNewEntity");
            csvWriter.write("LoadPrefetch");
            csvWriter.write("LoadCoChange");
            csvWriter.write("LoadChangeEntity");
            csvWriter.write("LoadNewEntity");
            csvWriter.endRecord();
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write(Integer.toString(cache.getTotalDuration()));
            csvWriter.write("0");
            csvWriter.write("false");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.write("0");
            csvWriter.endRecord();
            // else assume that the file already has the correct header line
            // write out record
            //XXX rewrite with built in iteratable
            for (CacheItem ci : cache){
            	//if(!ci.isInCache()) continue;
                csvWriter.write(Integer.toString(ci.getEntityId()));
                csvWriter.write(Integer.toString(ci.getLOC())); // LOC at time of last update
                csvWriter.write(Integer.toString(ci.getLoadCount()));
                csvWriter.write(Integer.toString(ci.getHitCount()));
                csvWriter.write(Integer.toString(ci.getMissCount()));
                csvWriter.write(Integer.toString(ci.getDuration()));
                csvWriter.write(ci.getReason());
                csvWriter.write(ci.isInCache()?"true":"false");
                int[] temp0 = ci.getHitTypeCnt();
                csvWriter.write(Integer.toString(temp0[0]));
                csvWriter.write(Integer.toString(temp0[1]));
                csvWriter.write(Integer.toString(temp0[2]));
                csvWriter.write(Integer.toString(temp0[3]));
                int[] temp1 = ci.getLoadTypeCnt();
                csvWriter.write(Integer.toString(temp1[0]));
                csvWriter.write(Integer.toString(temp1[1]));
                csvWriter.write(Integer.toString(temp1[2]));
                csvWriter.write(Integer.toString(temp1[3]));
                csvWriter.endRecord();
            }

            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    
    public void outputFileChangeReason() {
    	//输出最终缓存中的文件列表
        csvWriter = new CsvWriter(result_dir + filename + "_filechangereason.csv");
        csvWriter.setComment('#');
        try {
            // csvWriter.write("# number of hit, misses and time stayed in Cache for every file");
            csvWriter.writeComment("number of hit, misses and time stayed in Cache for every file");
            csvWriter.writeComment("project: " + pid +  ", total_files: " + total_files + ", total_files_alive: " + total_files_alive  + ", cacheTableSize: " + cache.getCacheSizeEver() + ", cachesize: " + cachesize
                    + ", blocksize: " + cachesize + ", prefetchsize: "
                    + prefetchsize + ", cache replacement policy: " + cacheRep);
            csvWriter.write("file_id");
            csvWriter.write("loc");
            csvWriter.write("last_type");
            csvWriter.write("duration");
            csvWriter.endRecord();
            // else assume that the file already has the correct header line
            // write out record
            //XXX rewrite with built in iteratable
            for (CacheItem ci : cache){
            	//if(!ci.isInCache()) continue;
            	for (int[] item : ci.getLastTypeCnt()){
                csvWriter.write(Integer.toString(ci.getEntityId()));
                csvWriter.write(Integer.toString(ci.getLOC())); // LOC at time of last update
                csvWriter.write(Integer.toString(item[0]));
                csvWriter.write(Integer.toString(item[1]));
                csvWriter.endRecord();
            	}
            }
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    
    public static void outputParameterTune(CsvWriter csvTempWriter, ArrayList<Double[]> paras) {
    	//输出最终缓存中的文件列表
        try {
        	csvTempWriter.write("cachesize");
        	csvTempWriter.write("blocksize");
        	csvTempWriter.write("prefetchsize");
        	csvTempWriter.write("hitrate");
        	csvTempWriter.endRecord();
            // else assume that the file already has the correct header line
            // write out record
            //XXX rewrite with built in iteratable
            for (Double[] item : paras){
            	csvTempWriter.write(Double.toString(item[0]));
            	csvTempWriter.write(Double.toString(item[1]));
            	csvTempWriter.write(Double.toString(item[2]));
            	csvTempWriter.write(Double.toString(item[3]));
            	csvTempWriter.endRecord();
            }
            csvTempWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private int getCommitCount() {
        return commits;
    }

    public CsvWriter getCsvWriter() {
        return csvWriter;
    }
}
