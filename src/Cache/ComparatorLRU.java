package Cache;

import java.util.Comparator;

public class ComparatorLRU implements Comparator<CacheItem> {
	//替换策略，按照时间从前向后排序
    public int compare(CacheItem c1, CacheItem c2) {
        int d1 = c1.getLoadedDate();
        int d2 = c2.getLoadedDate();
        
        assert (d1 != d2); // load time should be distinct

        if (d1 < d2)
            return -1;
        else
            return 1;
    }
}
