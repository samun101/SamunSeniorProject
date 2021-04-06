package com.example.myapp.myapp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.auth.policy.Condition;
import com.amazonaws.auth.policy.Policy;
import com.amazonaws.auth.policy.Principal;
import com.amazonaws.auth.policy.Resource;
import com.amazonaws.auth.policy.Statement;
import com.amazonaws.auth.policy.Statement.Effect;
import com.amazonaws.auth.policy.actions.SQSActions;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.CreateTopicRequest;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.SetQueueAttributesRequest;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.DocumentLocation;
import com.amazonaws.services.textract.model.DocumentMetadata;
import com.amazonaws.services.textract.model.GetDocumentTextDetectionRequest;
import com.amazonaws.services.textract.model.GetDocumentTextDetectionResult;
import com.amazonaws.services.textract.model.NotificationChannel;
import com.amazonaws.services.textract.model.Relationship;
import com.amazonaws.services.textract.model.S3Object;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionRequest;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;;
public class DocumentProcessor {

    private static String sqsQueueName=null;
    private static String snsTopicName=null;
    private static String snsTopicArn = null;
    private static String roleArn= null;
    private static String sqsQueueUrl = null;
    private static String sqsQueueArn = null;
    private static String startJobId = null;
    private static String bucket = null;
    private static String document = null; 
    private static AmazonSQS sqs=null;
    private static AmazonSNS sns=null;
    private static AmazonTextract textract = null;

    public enum ProcessType {
        DETECTION
    }

    public static void main(String[] args) throws Exception {
        
        String document = "volo2IMG.pdf";
        String bucket = "samunbucket";
        String roleArn="arn:aws:iam::534755077870:role/TextractRole";
        Regions clientRegion = Regions.US_EAST_2;
        
        sns = AmazonSNSClientBuilder.standard().withRegion(clientRegion).build();
        sqs= AmazonSQSClientBuilder.standard().withRegion(clientRegion).build();
        
        // Build Textract Client
        EndpointConfiguration endpoint = new EndpointConfiguration(
                "https://textract.us-east-2.amazonaws.com", "us-east-2");
        textract = AmazonTextractClientBuilder.standard()
                .withEndpointConfiguration(endpoint).build();

        
        CreateTopicandQueue();
        saveString(ProcessDocument(bucket,document,roleArn,ProcessType.DETECTION));
        
        DeleteTopicandQueue();
        System.out.println("Done!");
        
        
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
    static void DeleteTopicandQueue()
    {
        if (sqs !=null) {
            sqs.deleteQueue(sqsQueueUrl);
            System.out.println("SQS queue deleted");
        }
        
        if (sns!=null) {
            sns.deleteTopic(snsTopicArn);
            System.out.println("SNS topic deleted");
        }
    }
    
    //Starts the processing of the input document.
    static String ProcessDocument(String inBucket, String inDocument, String inRoleArn, ProcessType type) throws Exception
    {
        bucket=inBucket;
        document=inDocument;
        roleArn=inRoleArn;
        String ret = ""; 
        switch(type)
        {
            case DETECTION:
                StartDocumentTextDetection(bucket, document);
                System.out.println("Processing type: Detection");
                break;
            default:
                System.out.println("Invalid processing type. Choose Detection or Analysis");
                throw new Exception("Invalid processing type");
           
        }

        System.out.println("Waiting for job: " + startJobId);
        //Poll queue for messages
        List<Message> messages=null;
        int dotLine=0;
        boolean jobFound=false;

        //loop until the job status is published. Ignore other messages in queue.
        do{
            messages = sqs.receiveMessage(sqsQueueUrl).getMessages();
            if (dotLine++<40){
                System.out.print(".");
            }else{
                System.out.println();
                dotLine=0;
            }

            if (!messages.isEmpty()) {
                //Loop through messages received.
                for (Message message: messages) {
                    String notification = message.getBody();

                    // Get status and job id from notification.
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode jsonMessageTree = mapper.readTree(notification);
                    JsonNode messageBodyText = jsonMessageTree.get("Message");
                    ObjectMapper operationResultMapper = new ObjectMapper();
                    JsonNode jsonResultTree = operationResultMapper.readTree(messageBodyText.textValue());
                    JsonNode operationJobId = jsonResultTree.get("JobId");
                    JsonNode operationStatus = jsonResultTree.get("Status");
                    System.out.println("Job found was " + operationJobId);
                    
                    // Found job. Get the results and display.
                    if(operationJobId.asText().equals(startJobId)){
                        jobFound=true;
                        System.out.println("Job id: " + operationJobId );
                        System.out.println("Status : " + operationStatus.toString());
                        if (operationStatus.asText().equals("SUCCEEDED")){
                            switch(type)
                            {
                                case DETECTION:
                                    ret = GetDocumentTextDetectionResults();
                                    break;
                                default:
                                    System.out.println("Invalid processing type. Choose Detection or Analysis");
                                    throw new Exception("Invalid processing type");
                               
                            }
                        }
                        else{
                            System.out.println("Document analysis failed");
                            System.out.println(messageBodyText);
                        }

                        sqs.deleteMessage(sqsQueueUrl,message.getReceiptHandle());
                    }

                    else{
                        System.out.println("Job received was not job " +  startJobId);
                        //Delete unknown message. Consider moving message to dead letter queue
                        sqs.deleteMessage(sqsQueueUrl,message.getReceiptHandle());
                    }
                }
            }
            else {
                Thread.sleep(5000);
            }
        } while (!jobFound);
        
        System.out.println("Finished processing document");
        return ret;
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
    
    private static void StartDocumentTextDetection(String bucket, String document) throws Exception{

        //Create notification channel 
        NotificationChannel channel= new NotificationChannel()
                .withSNSTopicArn(snsTopicArn)
                .withRoleArn(roleArn);
        System.out.println(snsTopicArn);
        StartDocumentTextDetectionRequest req = new StartDocumentTextDetectionRequest()
                .withDocumentLocation(new DocumentLocation()
                    .withS3Object(new S3Object()
                        .withBucket(bucket)
                        .withName(document)))
                .withJobTag("DetectingText")
                .withNotificationChannel(channel);

        StartDocumentTextDetectionResult startDocumentTextDetectionResult = textract.startDocumentTextDetection(req);
        startJobId=startDocumentTextDetectionResult.getJobId();
    }
    
  //Gets the results of processing started by StartDocumentTextDetection
    private static String GetDocumentTextDetectionResults() throws Exception{
        int maxResults=1000;
        String paginationToken=null;
        GetDocumentTextDetectionResult response=null;
        Boolean finished=false;
        String ret = "";
        String temp = "";
        Integer count = 1;
        while (finished==false)
        {
            GetDocumentTextDetectionRequest documentTextDetectionRequest= new GetDocumentTextDetectionRequest()
                    .withJobId(startJobId)
                    .withMaxResults(maxResults)
                    .withNextToken(paginationToken);
            response = textract.getDocumentTextDetection(documentTextDetectionRequest);
            DocumentMetadata documentMetaData=response.getDocumentMetadata();

            System.out.println("Pages: " + documentMetaData.getPages().toString());
            
            //Show blocks information
            List<Block> blocks= response.getBlocks();
            for (Block block : blocks) {
            	//Here is where data processing goes
            	if(block.getBlockType().equals("LINE")) {
            		if(block.getText().equals(block.getText().toUpperCase())) {
            			temp = DisplayBlockInfo(block);
            			count++;
            		}
            		if(count >4) {
            			count =0;
            		}
            		if(count >0 ) {
            			if(block.getText().contains("Medium")) {
            				count = 0;
            				ret = ret+temp;
            				temp = "";
            			}
            			else {
            				count++;
            			}
            		}            		
            	}        	
                //ret = ret +DisplayBlockInfo(block);
            }
            paginationToken=response.getNextToken();
            if (paginationToken==null)
                finished=true;
            
        }
        return ret;
    }

    //Displays Block information for text detection and text analysis
    private static String DisplayBlockInfo(Block block) {
    	String ret = "";
    	if(block.getBlockType().equals("LINE")) {
	        //System.out.println("Block Id : " + block.getId());
	        if (block.getText()!=null) {
	            //System.out.println("\tDetected text: " + block.getText());
	            ret = ret + block.getText() +"\n";
	        }
	        if(block.getPage()!=null) {
	            //System.out.println("\tPage: " + block.getPage());            
	        //System.out.println();
	    	}
    	}
    	return ret;
    	
    }
    	
}