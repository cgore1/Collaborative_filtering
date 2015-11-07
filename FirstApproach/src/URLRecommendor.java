import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;



public class URLRecommendor {
	
	public enum Method
	{
		COSINE,
		EUCLIDEAN,
		JACCARD,
		DICE
	};

	private static final double THRESHOLD_SCORE = 0.5; 
	private static int THRESHOLD_URL;
	private static int THRESHOLD_USER;
	
	private static Map<String, URL> urlsMap = new HashMap<String, URL>(); 
	private static Set<String> userSet = new HashSet<String>();
	
	public static void main(String[] args) {
		log("Initializing..");
		initUrls();
		log("Init done!\n");
		
		List<Double> scores = new ArrayList<Double>();
		List<Double> actualValues = new ArrayList<Double>();
		
		// Vary parameters here..
		THRESHOLD_URL = 5;
		THRESHOLD_USER = 5;
		weightOfUserSim = 0.5;
		int urlIndex = 4000;
		Method method = Method.COSINE;
		
		for(URL url : urlsMap.values()) 
		{
			for(String user : url.users)
			{
				double score = computeRecommendationScore(user, url, method);
				if(score != -1)
				{
					score = (score > THRESHOLD_SCORE) ? 1 : 0;
					scores.add(score);
					actualValues.add(1.0);
					
				}
			}
			
			int index = url.users.size() * 2;
			
			for(String user : userSet)
			{
				if(!url.users.contains(user))
				{
					double score = computeRecommendationScore(user, url, method);
					if(score != -1)
					{
						score = (score > THRESHOLD_SCORE) ? 1 : 0;
						scores.add(score);
						actualValues.add(0.0);
					}
				}
				
				index--;
				if(index == 0) break;
			}
			
			urlIndex --;
			if(urlIndex == 0) break;
		}
		
		System.out.println("Error is " + Evaluator.getRMSError(actualValues, scores));
		
		log("Recommendations done!!\n");
	}

	static double weightOfUserSim = 0.5;

	private static void initUrls() 
	{
		Connection connection = null;
		try
		{
			connection = DriverManager.getConnection("jdbc:sqlite:D:\\data\\streams\\urldb.db");

			//Set<String> userSet = new HashSet<String>();

			// Active URLS
			log("calc active urls and users..");

			Statement urlStatement = connection.createStatement();
			String user_url_query = "SELECT url, user FROM url_user " +
					"WHERE url IN " + 
					"(SELECT url FROM url_user GROUP BY url HAVING COUNT(url) >=" + THRESHOLD_URL +") " +
					"AND user IN " + 
					"(SELECT user FROM url_user GROUP BY user HAVING COUNT(user) >=" + THRESHOLD_USER +") ";

			ResultSet urlResultSet = urlStatement.executeQuery(user_url_query);

			while(urlResultSet.next())
			{
				String urlStr = urlResultSet.getString("url");
				URL url = urlsMap.get(urlStr);
				if(url == null)
				{
					url = new URL(urlStr);
					urlsMap.put(urlStr, url);
				}

				String userName = urlResultSet.getString("user");
				url.users.add(userName);
				userSet.add(userName);
			}

			log("done active urls and users!!");
			log("Found " + urlsMap.size() + " active urls!");
			log("Found " + userSet.size() + " active users!");

			// hashtags
			log("calc hashtags..");

			int hashCount = 1;
			Statement hashTagStatement = connection.createStatement();
			String hashTagQuery = "SELECT url, hashtag "
					+ " FROM url_hashtag "
					+ "WHERE url IN "
					+ "(SELECT url FROM url_user GROUP BY url HAVING COUNT(url) >=" + THRESHOLD_URL +")";
			ResultSet hashTagResultSet = hashTagStatement.executeQuery(hashTagQuery);

			while(hashTagResultSet.next())
			{
				String urlStr = hashTagResultSet.getString("url");
				URL url = urlsMap.get(urlStr);

				if(url == null)
					continue;

				url.hashTags.add(hashTagResultSet.getString("hashtag"));
				hashCount++;
			}

			log("done hashtags!!");
			log("Found " + hashCount + " corresponding hashtags!\n");
		}
		catch(Exception se)
		{
			se.printStackTrace();
		}
		finally 
		{
			if(connection != null)
			{
				try {
					connection.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static double computeWeightedScore(double userSim, double hashTagSim)
	{
		return userSim * weightOfUserSim + hashTagSim * (1 - weightOfUserSim);
	}
	
	private static double computeRecommendationScore(String user, URL u1, Method method)
	{
		Map<String, URL> user_urlsMap = new HashMap<String, URL>(); 
		for( URL u: urlsMap.values())
		{
			if(u.users.contains(user) && !u.equals(u1))
			{
				user_urlsMap.put(u.url, u);
			}
		}
		
		if(user_urlsMap.size() == 0)
			return -1.0; // something very bad happened!

		double[] userDistances=new double[user_urlsMap.size()];
		double[] hashtagDistances=new double[user_urlsMap.size()];
		double[] urlDistance=new double[user_urlsMap.size()];
		
		int index=0;
		for(URL u2:user_urlsMap.values())
		{
			userDistances[index]=SimilarityCalculator.getDistance(u1.users,u2.users, method);

			if(u1.hashTags.size() == 0 || u2.hashTags.size() == 0)
			{
				hashtagDistances[index] = 0;
				urlDistance[index]= userDistances[index];
			}
			else
			{
				hashtagDistances[index] = SimilarityCalculator.getDistance(u1.hashTags,u2.hashTags, method);
//				urlDistance[index]=(userDistances[index] + hashtagDistances[index])/2;
				urlDistance[index] = computeWeightedScore(userDistances[index], hashtagDistances[index]);
			}

			index++;
		}

		if(method.equals(Method.EUCLIDEAN))
		{
			Arrays.sort(urlDistance);
		}
		else
		{
 			Arrays.sort(Arrays.asList(urlDistance).toArray(), Collections.reverseOrder());
		}
		
		int avgsize = (urlDistance.length + 1)/2;
		double averageDistance=0;
		double recommendationScore;
		for( int i=0;i<avgsize;i++)
		{
			averageDistance = averageDistance + urlDistance[i];
		}

		recommendationScore = averageDistance/avgsize;
		return recommendationScore;
	}
	
	private static void log(String message)
	{
		System.out.println(new Timestamp(new Date().getTime()) + " : " + message);
	}
}