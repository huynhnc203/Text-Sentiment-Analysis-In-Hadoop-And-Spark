import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.Counters;

import java.io.*;
import java.io.IOException;
import java.util.*;
import java.nio.charset.StandardCharsets;

/*
Execution Guide:
hadoop com.sun.tools.javac.Main NB.java
jar cf NB.jar NB*.class
hadoop jar NB.jar NB
hadoop fs -cat output/part-r-00000
*/

public class NB
{
	public static enum Global_Counters 
	{
		TWEETS_SIZE,
		POS_TWEETS_SIZE,
		NEG_TWEETS_SIZE,
		POS_WORDS_SIZE,
		NEG_WORDS_SIZE,
		FEATURES_SIZE,
		TRUE_POSITIVE_RATE,
		FALSE_POSITIVE_RATE,
		TRUE_NEGATIVE_RATE,
		FALSE_NEGATIVE_RATE
	}



	/* input:  <byte_offset, line_of_tweet>
     * output: <word, sentiment>
     */
	public static class Map_Training extends Mapper<Object, Text, Text, Text> 
	{
		private Text tweet_key = new Text();
        private Text word_value = new Text();
		
		public void map(Object key, Text value, Context context) throws IOException, InterruptedException 
		{
			context.getCounter(Global_Counters.TWEETS_SIZE).increment(1);


			String line = value.toString();
			String[] columns = line.split(",");

			// if the columns are more than 4, that means the text of the post had commas inside,  
            // so stitch the last columns together to form the full text of the tweet
            if(columns.length > 4)
            {
                for(int i=4; i<columns.length; i++)
                    columns[3] += columns[i];
            }

            String tweet_id = columns[0];
            String tweet_sentiment = columns[1];
            String tweet_text = columns[3];

            String sentiment_label = "POSITIVE";

            if(tweet_sentiment.equals("1"))
            {
            	context.getCounter(Global_Counters.POS_TWEETS_SIZE).increment(1);
            	context.getCounter(Global_Counters.POS_WORDS_SIZE).increment(tweet_text.split("\\s+").length);
            }
            else
            {
            	context.getCounter(Global_Counters.NEG_TWEETS_SIZE).increment(1);
            	context.getCounter(Global_Counters.NEG_WORDS_SIZE).increment(tweet_text.split("\\s+").length);
            	sentiment_label = "NEGATIVE";
            }


            // clean the text of the tweet from links..
            tweet_text = tweet_text.replaceAll("(http|https)\\:\\/\\/[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,3}(\\/\\S*)?", "")
                                .replaceAll("#|@|&.*?\\s", "")	// mentions, hashtags, special characters...
                                .replaceAll("\\d+", "")			// numbers...
                                .replaceAll("[^a-zA-Z ]", "")	// punctuation...
                                .toLowerCase()  				// turn every character left to lowercase...
                                .trim()         				// trim the spaces before & after the whole string...
                                .replaceAll("\\s+", " "); 		// and get rid of double spaces


            if(tweet_text != null && !tweet_text.trim().isEmpty())
            {
	            String[] tweet_words = tweet_text.split(" ");

	            for(String word : tweet_words)
	                context.write(new Text(word), new Text(sentiment_label));
            }	   
		}
    }

    /* input:  <word, sentiment>
     * output: <word, pos_wordcount@neg_wordcount>
     */
	public static class Reduce_Training extends Reducer<Text, Text, Text, Text> 
	{		
		public void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException 
		{
			context.getCounter(Global_Counters.FEATURES_SIZE).increment(1);

			int positive_counter = 0;
			int negative_counter = 0;

			// for each word, count the occurences in tweets with positive/negative sentiment
			for(Text value : values)
			{
				String sentiment = value.toString();
				if(sentiment.equals("POSITIVE"))
					positive_counter++;
				else
					negative_counter++;
			}

			context.write(key, new Text(String.valueOf(positive_counter) + "@" + String.valueOf(negative_counter)));
		}
    }



    /* input: <byte_offset, line_of_tweet>
     * output: <tweet@tweet_text, sentiment>
     */
	public static class Map_Testing extends Mapper<Object, Text, Text, Text> 
	{
        int features_size, tweets_size, pos_tweets_size, neg_tweets_size, pos_words_size, neg_words_size;
        Double pos_class_probability, neg_class_probability;

        // hashmaps with each word as key and its number of occurences in each class as value
        HashMap<String, Integer> pos_words = new HashMap<String, Integer>();
        HashMap<String, Integer> neg_words = new HashMap<String, Integer>();

        // hashmaps with each word as key and its probability in each class as value
        HashMap<String, Double> pos_words_probabilities = new HashMap<String, Double>();
        HashMap<String, Double> neg_words_probabilities = new HashMap<String, Double>();

        // lists holding all probabilities to be multiplied together, along with the positive/negative class probability
        ArrayList<Double> pos_probabilities_list = new ArrayList<Double>();
        ArrayList<Double> neg_probabilities_list = new ArrayList<Double>();

        protected void setup(Context context) throws IOException, InterruptedException 
		{
			// load all counters to be used for the calculation of the probabilities
			features_size = Integer.parseInt(context.getConfiguration().get("features_size"));
			tweets_size = Integer.parseInt(context.getConfiguration().get("tweets_size"));
			pos_tweets_size = Integer.parseInt(context.getConfiguration().get("pos_tweets_size"));
			neg_tweets_size = Integer.parseInt(context.getConfiguration().get("neg_tweets_size"));
			pos_words_size = Integer.parseInt(context.getConfiguration().get("pos_words_size"));
			neg_words_size = Integer.parseInt(context.getConfiguration().get("neg_words_size"));

			pos_class_probability = ((double) pos_tweets_size) / tweets_size;
			neg_class_probability = ((double) neg_tweets_size) / tweets_size;

			// load the model of the last training job and fill two hashmaps of words with the number of
			// occurences in positive and negative tweets
			Path training_model = new Path("training");
			FileSystem model_fs = training_model.getFileSystem(context.getConfiguration());
	        FileStatus[] file_status = model_fs.listStatus(training_model);

	        for(FileStatus i : file_status)
	        {
	        	Path current_file_path = i.getPath();

	        	if(i.isFile())
	        	{
	        		BufferedReader br = new BufferedReader(new InputStreamReader(model_fs.open(current_file_path)));
	        		String line; 

					while((line = br.readLine()) != null)
					{
			            String[] columns = line.toString().split("\t");
			            String[] pos_and_neg_counts = columns[1].split("@");

			            pos_words.put(columns[0], Integer.parseInt(pos_and_neg_counts[0]));
			            neg_words.put(columns[0], Integer.parseInt(pos_and_neg_counts[1]));
					}

			        br.close();
	        	}
	        }

	        // calculate all the word probabilities for positive and negative class (with laplace smoothing)
	        for(Map.Entry<String,Integer> entry : pos_words.entrySet()) 
        	{
				pos_words_probabilities.put(entry.getKey(), ((double) entry.getValue() + 1) / (pos_words_size + features_size));
				neg_words_probabilities.put(entry.getKey(), ((double) neg_words.get(entry.getKey()) + 1) / (neg_words_size + features_size));
        	}
		}
		
		public void map(Object key, Text value, Context context) throws IOException, InterruptedException 
		{
			String line = value.toString();
			String[] columns = line.split(",");

			// if the columns are more than 4, that means the text of the post had commas inside,  
            // so stitch the last columns together to form the post
            if(columns.length > 4)
            {
                for(int i=4; i<columns.length; i++)
                    columns[3] += columns[i];
            }

            String tweet_id = columns[0];
            String tweet_sentiment = columns[1];
            String tweet_text = columns[3];

            // clean the text of the tweet from links..
            tweet_text = tweet_text.replaceAll("(http|https)\\:\\/\\/[a-zA-Z0-9\\-\\.]+\\.[a-zA-Z]{2,3}(\\/\\S*)?", "")
                                .replaceAll("#|@|&.*?\\s", "")	// mentions, hashtags, special characters...
                                .replaceAll("\\d+", "")			// numbers...
                                .replaceAll("[^a-zA-Z ]", "")	// punctuation...
                                .toLowerCase()  				// turn every character left to lowercase...
                                .trim()         				// trim the spaces before & after the whole string...
                                .replaceAll("\\s+", " "); 		// and get rid of double spaces

          	// initialize the probabilities with the class probability of each sentiment
            Double pos_probability = pos_class_probability;
            Double neg_probability = neg_class_probability;

            // calculate the product of the probabilities of the words (+ the class probability) for each class
            if(tweet_text != null && !tweet_text.trim().isEmpty())
            {
	            String[] tweet_words = tweet_text.split(" ");

	            for(String word : tweet_words)
	            {
	            	for(Map.Entry<String,Double> entry : pos_words_probabilities.entrySet()) 
	            	{
	            		if(word.equals(entry.getKey()))
	            		{
							pos_probability = ((double) pos_probability) * pos_words_probabilities.get(word);
							neg_probability = ((double) neg_probability) * neg_words_probabilities.get(word);
	            		}
	            	}
	            }
	        }

	        // compare and set the max value of the two class probabilities as the result of the guessed sentiment for every tweet
	        if(Double.compare(pos_probability, neg_probability) > 0)
	        {
	        	if(tweet_sentiment.equals("1"))
	        		context.getCounter(Global_Counters.TRUE_POSITIVE_RATE).increment(1);
	        	else
	        		context.getCounter(Global_Counters.FALSE_POSITIVE_RATE).increment(1);

            	context.write(new Text(tweet_id + "@" + tweet_text), new Text("POSITIVE"));
	        }
           	else
           	{
           		if(tweet_sentiment.equals("0"))
	        		context.getCounter(Global_Counters.TRUE_NEGATIVE_RATE).increment(1);
	        	else
	        		context.getCounter(Global_Counters.FALSE_NEGATIVE_RATE).increment(1);

           		context.write(new Text(tweet_id + "@" + tweet_text), new Text("NEGATIVE"));
           	}
		}
    }



	public static void main(String[] args) throws Exception 
	{
		// paths to directories were inbetween and final job outputs are stored
		Path input_dir = new Path("input");
    	Path training_dir = new Path("training");
    	Path testing_dir = new Path("test_data");
    	Path output_dir = new Path("output");

	    Configuration conf = new Configuration();

	    FileSystem fs = FileSystem.get(conf);
    	if(fs.exists(training_dir))
    		fs.delete(training_dir, true);
    	if(fs.exists(output_dir))
    		fs.delete(output_dir, true);

    	long start_time = System.nanoTime();

		Job training_job = Job.getInstance(conf, "Training");
		training_job.setJarByClass(NB.class);
		training_job.setMapperClass(Map_Training.class);
		training_job.setReducerClass(Reduce_Training.class);	
		training_job.setMapOutputKeyClass(Text.class);
		training_job.setMapOutputValueClass(Text.class);
		training_job.setOutputKeyClass(Text.class);
		training_job.setOutputValueClass(Text.class);
		FileInputFormat.addInputPath(training_job, input_dir);
		FileOutputFormat.setOutputPath(training_job, training_dir);
		training_job.waitForCompletion(true);

		int tweets_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.TWEETS_SIZE).getValue());
		conf.set("tweets_size", String.valueOf(tweets_size));
		int pos_tweets_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.POS_TWEETS_SIZE).getValue());
		conf.set("pos_tweets_size", String.valueOf(pos_tweets_size));
		int neg_tweets_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.NEG_TWEETS_SIZE).getValue());
		conf.set("neg_tweets_size", String.valueOf(neg_tweets_size));
		int pos_words_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.POS_WORDS_SIZE).getValue());
		conf.set("pos_words_size", String.valueOf(pos_words_size));
		int neg_words_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.NEG_WORDS_SIZE).getValue());
		conf.set("neg_words_size", String.valueOf(neg_words_size));
		int features_size = Math.toIntExact(training_job.getCounters().findCounter(Global_Counters.FEATURES_SIZE).getValue());
		conf.set("features_size", String.valueOf(features_size));

		Job testing_job = Job.getInstance(conf, "Testing");
		testing_job.setJarByClass(NB.class);
		testing_job.setMapperClass(Map_Testing.class);	
		testing_job.setMapOutputKeyClass(Text.class);
		testing_job.setMapOutputValueClass(Text.class);
		testing_job.setOutputKeyClass(Text.class);
		testing_job.setOutputValueClass(Text.class);
		FileInputFormat.addInputPath(testing_job, testing_dir);
		FileOutputFormat.setOutputPath(testing_job, output_dir);
		testing_job.waitForCompletion(true);

		System.out.println("EXECUTION DURATION: " + (System.nanoTime() - start_time) / 1000000000F + " seconds");

		int tp = Math.toIntExact(testing_job.getCounters().findCounter(Global_Counters.TRUE_POSITIVE_RATE).getValue());
		int fp = Math.toIntExact(testing_job.getCounters().findCounter(Global_Counters.FALSE_POSITIVE_RATE).getValue());
		int tn = Math.toIntExact(testing_job.getCounters().findCounter(Global_Counters.TRUE_NEGATIVE_RATE).getValue());
		int fn = Math.toIntExact(testing_job.getCounters().findCounter(Global_Counters.FALSE_NEGATIVE_RATE).getValue());

		System.out.println("\nCONFUSION MATRIX:");
		System.out.printf("%-10s %-10s \n", tp, fp);
		System.out.printf("%-10s %-10s \n\n", fn, tn);

		System.out.printf("%-25s %-10s \n", "SENSITIVITY: ", ((double) tp) / (tp + fn));
		System.out.printf("%-25s %-10s \n", "PRECISION: ", ((double) tp) / (tp + fp));
		System.out.printf("%-25s %-10s \n", "ACCURACY: ", ((double) (tp + tn)) / (tp + tn + fp + fn));
		System.out.printf("%-25s %-10s \n", "BALANCED ACCURACY: ", ((double) (((double) tp) / (tp + fn) + ((double) tn) / (tn + fp))) / 2);
		System.out.printf("%-25s %-10s \n", "F1 SCORE: ", ((double) (2 * tp)) / (2 * tp + fp + fn));
  	}
}