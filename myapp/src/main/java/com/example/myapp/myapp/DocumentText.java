//Calls DetectDocumentText.
//Loads document from S3 bucket. Displays the document and bounding boxes around detected lines/words of text.
package com.example.myapp.myapp;

import java.lang.*;
import java.awt.*;
import java.awt.image.BufferedImage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.io.File;  // Import the File class
import java.io.IOException;  // Import the IOException class to handle errors
import java.io.FileWriter;   // Import the FileWriter class
import javax.swing.*;


import com.amazonaws.regions.Regions;
import com.amazonaws.AmazonServiceException;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;

import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.DetectDocumentTextRequest;
import com.amazonaws.services.textract.model.DetectDocumentTextResult;
import com.amazonaws.services.textract.model.Document;
import com.amazonaws.services.textract.model.S3Object;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import org.json.simple.JSONObject;

public class DocumentText extends JPanel {

    private static final long serialVersionUID = 1L;

    BufferedImage image;
    DetectDocumentTextResult result;

    public DocumentText(DetectDocumentTextResult documentResult, BufferedImage bufImage) throws Exception {
        super();
        
        result = documentResult; // Results of text detection.
        image = bufImage; // The image containing the document.

    }

   
    public static void saveString(String str) throws Exception{ //small function to help save strings and make sure I'm getting the content correctly
    	try {
        	File myObj = new File("C:\\Users\\Sam\\Documents\\toString.txt");
            if (myObj.createNewFile()) {
              System.out.println("File created: " + myObj.getName());
            } else {
              System.out.println("File already exists.");
            }
          } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
          }
        try {
            FileWriter myWriter = new FileWriter("C:\\Users\\Sam\\Documents\\toString.txt");
            myWriter.write(str);
            myWriter.close();
            System.out.println("Successfully wrote to the file.");
          } catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
          }
    }
    
    public static JSONObject objectify(DetectDocumentTextResult res) throws Exception {
    	JSONObject ret = new JSONObject();
    	JSONObject temp;
    	String type, text;
    	Float left, top;
    	int base = 3;  //Cambion start
    	//int base = 8;	//Imp start
    	int ind = 1;
    	int baseTop = 3;

    	for(Block block:res.getBlocks()) {
    		temp = new JSONObject();
    		type = block.getBlockType();
    		text = block.getText();
    		left = block.getGeometry().getBoundingBox().getLeft();
    		top = block.getGeometry().getBoundingBox().getTop();
    		if((type.equals("LINE")) && (Math.abs((int)(left*100)-base)<3)) {
    			temp.put("left",(int)(left*100));
    			temp.put("text",text);
    			temp.put("top",top);
    			ret.put(ind,temp);
    			ind+=1;
    		}
    	}
    	for(Block block:res.getBlocks()) {
    		temp = new JSONObject();
    		type = block.getBlockType();
    		text = block.getText();
    		left = block.getGeometry().getBoundingBox().getLeft();
    		top = block.getGeometry().getBoundingBox().getTop();
    		if((type.equals("LINE")) && !(Math.abs((int)(left*100)-base)<3) && (int)(top*100) >= baseTop) {
    			temp.put("left",(int)(left*100));
    			temp.put("text",text);
    			temp.put("top",top);
    			ret.put(ind,temp);
    			ind+=1;
    		}
    	}
    	return ret;
    }
 // Creates an SNS topic and SQS queue. The queue is subscribed to the topic. 
    static void CreateTopicandQueue()
    {
        //create a new SNS topic
        snsTopicName="AmazonTextractTopic" + Long.toString(System.currentTimeMillis());
        CreateTopicRequest createTopicRequest = new CreateTopicRequest(snsTopicName);
        CreateTopicResult createTopicResult = sns.createTopic(createTopicRequest);
        snsTopicArn=createTopicResult.getTopicArn();
        
        //Create a new SQS Queue
        sqsQueueName="AmazonTextractQueue" + Long.toString(System.currentTimeMillis());
        final CreateQueueRequest createQueueRequest = new CreateQueueRequest(sqsQueueName);
        sqsQueueUrl = sqs.createQueue(createQueueRequest).getQueueUrl();
        sqsQueueArn = sqs.getQueueAttributes(sqsQueueUrl, Arrays.asList("QueueArn")).getAttributes().get("QueueArn");
        
        //Subscribe SQS queue to SNS topic
        String sqsSubscriptionArn = sns.subscribe(snsTopicArn, "sqs", sqsQueueArn).getSubscriptionArn();
        
        // Authorize queue
          Policy policy = new Policy().withStatements(
                  new Statement(Effect.Allow)
                  .withPrincipals(Principal.AllUsers)
                  .withActions(SQSActions.SendMessage)
                  .withResources(new Resource(sqsQueueArn))
                  .withConditions(new Condition().withType("ArnEquals").withConditionKey("aws:SourceArn").withValues(snsTopicArn))
                  );
                  

          Map queueAttributes = new HashMap();
          queueAttributes.put(QueueAttributeName.Policy.toString(), policy.toJson());
          sqs.setQueueAttributes(new SetQueueAttributesRequest(sqsQueueUrl, queueAttributes)); 
          

         System.out.println("Topic arn: " + snsTopicArn);
         System.out.println("Queue arn: " + sqsQueueArn);
         System.out.println("Queue url: " + sqsQueueUrl);
         System.out.println("Queue sub arn: " + sqsSubscriptionArn );
     }
    public static void main(String arg[]) throws Exception {
        
        // The S3 bucket and document
        String document = "ExampleMonster.PNG";
        String bucket = "samunbucket";
        Regions clientRegion = Regions.US_EAST_2;
        
        // Call DetectDocumentText
        EndpointConfiguration endpoint = new EndpointConfiguration(
                "https://textract.us-east-2.amazonaws.com", "us-east-2");
        AmazonTextract client = AmazonTextractClientBuilder.standard()
                .withEndpointConfiguration(endpoint).build();

        AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(clientRegion)
                .build();
        
        DetectDocumentTextRequest request = new DetectDocumentTextRequest()
            .withDocument(new Document().withS3Object(new S3Object().withName(document).withBucket(bucket)));

        DetectDocumentTextResult result = client.detectDocumentText(request);
        
        
        //Creating a JSONObject object
        JSONObject jsonObject = objectify(result);
        
        String res = " ";
        String res2 ="";
        for(int i =1;i<=77;i++) { //Shadesteel- 47,  IMP- 77,  Cambion-71
        	res = res + jsonObject.get(i).toString() + "\n";
        }

        //reading text and copying it to files
        for(Block block:result.getBlocks()) {
        	if(block.getBlockType().equals("LINE")) {
        		res2 = res2 + String.valueOf(block.getGeometry().getBoundingBox().getLeft())+" "+ block.getText()+" "+String.valueOf(block.getGeometry().getBoundingBox().getTop())+"\n";
        	}

        }
        System.out.println(res2);
        saveString(res);
        //saveString(jsonObject.toJSONString());
        
        DeleteObjectsRequest multiObjectDeleteRequest = new DeleteObjectsRequest(bucket)
                .withKeys(document)
                .withQuiet(false);
        
        try {
	        DeleteObjectsResult delObjRes = s3Client.deleteObjects(multiObjectDeleteRequest);
	        int successfulDeletes = delObjRes.getDeletedObjects().size();
	        System.out.println(successfulDeletes + " objects successfully deleted.");
        }
        catch (AmazonServiceException e) {
            // The call was transmitted successfully, but Amazon S3 couldn't process 
            // it, so it returned an error response.
            e.printStackTrace();
        } catch (SdkClientException e) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            e.printStackTrace();
        }
    }
}