/**
 * 
 */
package Cache;

import org.joda.time.DateTime;

/**
 * @author yueyang
 *
 */
public class TestSome {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		String time1 = "2001-01-23 05:10:39.0";
		String time2 = "2001-01-23 08:05:34.0";
		String time3=  "2001-01-23 08:44:46.0";
		System.out.println(Util.Dates.getMinuteDuration(time2, time1));
	}

}
