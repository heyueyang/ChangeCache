package Util;

import java.io.BufferedWriter;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;
import weka.core.converters.CSVSaver;

public class FileUtil {
	
	public static Instances ReadData(String filePath){
	    System.out.println("======Read data from " + filePath + "======");
		if(!new File(filePath).exists()){
			System.out.println("======This File doesn't exist!" + "======");
			return null;
		}
		Instances ResultIns = null;
		//��������ѡ��
		File fData = new File(filePath);	
		ArffLoader loader = new ArffLoader();
		try {
			loader.setFile(fData);
			ResultIns = loader.getDataSet();
			System.out.println("=========Data Information=========");
		    System.out.println("======AttrNum:"+ResultIns.numAttributes()+"======");
		    System.out.println("======InstancesNum:"+ResultIns.numInstances()+"======");
		} catch (IOException e) {
				// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		return ResultIns;
}
	
	public static Instances ReadDataCSV(String filePath){
	    System.out.println("======Read data from " + filePath + "======");
		if(!new File(filePath).exists()){
			System.out.println("======This File doesn't exist!" + "======");
			return null;
		}
		Instances ResultIns = null;
		//��������ѡ��
		File fData = new File(filePath);	
		CSVLoader loader = new CSVLoader();
		try {
			loader.setSource(fData);
			ResultIns = loader.getDataSet();
			System.out.println("=========Data Information=========");
		    System.out.println("======AttrNum:"+ResultIns.numAttributes()+"======");
		    System.out.println("======InstancesNum:"+ResultIns.numInstances()+"======");
		} catch (IOException e) {
				// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		return ResultIns;
}
	public static boolean WriteData(Instances ins,String filePath){
	    
		if(new File(filePath).exists()){
			System.out.println("======" + filePath + "already exist!======");
			return false;
		}
		ArffSaver saver = new ArffSaver(); 
	    saver.setInstances(ins);  
	    try {
			saver.setFile(new File(filePath));
			saver.writeBatch(); 
		    System.out.println("==Arff File Writed:"+filePath+"==");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}  
	    
	    return true;

		
}
public static boolean WriteArff2CSV(Instances ins,String filePath){
	    
		if(new File(filePath).exists()){
			System.out.println("======" + filePath + "already exist!======");
			return false;
		}
		weka.core.converters.CSVSaver saver = new CSVSaver();
	    saver.setInstances(ins);  
	    try {
			saver.setFile(new File(filePath));
			saver.writeBatch(); 
		    System.out.println("==CSV File Writed:"+filePath+"==");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}  
	    
	    return true;

		
}
	public static String getFileName(String filePath){
		return filePath.substring(filePath.lastIndexOf("\\")+1, filePath.lastIndexOf("."));//��ȡ�ļ���
	}
	
	public static int anomalyIndex(Instances ins){
		int valueCnt[] = {0,0};
	    int temp = 0;
	    int ind = 0;
	    valueCnt[0] = 0;
	    valueCnt[1] = 0;
	    for(int j=0;j < ins.numInstances(); j++){
	    		temp = (int) ins.instance(j).classValue();
	    		valueCnt[temp]++;
	    	}
	   	//ind = (valueCnt[0] > valueCnt[1]) ? valueCnt[1] : valueCnt[0];
	    ind = (valueCnt[0] > valueCnt[1]) ? 1 : 0;
	   	//System.out.println("======anomaly cnt="+valueCnt[0]+"======"+valueCnt[1]);
		return ind;
	 } 

	public static String Write2TempArff(List<Integer> fileList, Map<Integer, List<Integer>> data) throws IOException{
		String outPath = "/home/yueyang/workspace/ChangeCache/Results/association/" + "test.arff";
		BufferedWriter bWriter = new BufferedWriter(new FileWriter(outPath));
		//StringBuffer sb = new StringBuffer();
		bWriter.append("@relation files" + "\n");
		//bWriter.append("@attribute 'commit_id' numeric" + "\n");
		for(int i = 0 ; i < fileList.size(); i++){
			String file_id = String.valueOf(fileList.get(i));
			bWriter.append("@attribute 'file_" + file_id + "' { t}" + "\n");
		}
		bWriter.append("@data" + "\n");
		Set<Integer> commitIds = data.keySet();
		int[] cnt = new int[fileList.size()];
		for(Integer commit_id : commitIds){
			//System.out.print(n++ + ":" + "\t");
			List<Integer> list = data.get(commit_id);
			//for(int j = 0 ; j < list.size(); j++){
				//System.out.print(list.get(j) + "\t");
			//}
			//System.out.println();
		
			StringBuffer temp = new StringBuffer();
			for(int j = 0 ; j < fileList.size(); j++){
				//temp.append(commit_id+",");
				if(list.contains(fileList.get(j))){
					temp.append("t"+",");
					cnt[j]++;
				}else{
					temp.append("?"+",");
				}
			}
			bWriter.append(temp.subSequence(0, temp.length()-1)+"\r");
		}
			
			bWriter.flush();
			bWriter.close();
			System.out.println("writed into" + outPath);
	
			//for(int j = 0 ; j < fileList.size(); j++){
			//	System.out.println(j+ "," + fileList.get(j) + "," + cnt[j]);
			//}
			return outPath;
	}

}
