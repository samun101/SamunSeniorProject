//Calls DetectDocumentText.
//Loads document from S3 bucket. Displays the document and bounding boxes around detected lines/words of text.
package com.example.myapp.myapp;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.io.File;  // Import the File class
import java.io.IOException;  // Import the IOException class to handle errors
import java.io.FileWriter;   // Import the FileWriter class
import javax.swing.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.BoundingBox;
import com.amazonaws.services.textract.model.DetectDocumentTextRequest;
import com.amazonaws.services.textract.model.DetectDocumentTextResult;
import com.amazonaws.services.textract.model.Document;
import com.amazonaws.services.textract.model.S3Object;
import com.amazonaws.services.textract.model.Point;
import com.amazonaws.services.textract.model.Relationship;


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
    
    public static void main(String arg[]) throws Exception {
        
        // The S3 bucket and document
        String document = "Test.PNG";
        String bucket = "samunbucket";

        

        // Call DetectDocumentText
        EndpointConfiguration endpoint = new EndpointConfiguration(
                "https://textract.us-east-2.amazonaws.com", "us-east-2");
        AmazonTextract client = AmazonTextractClientBuilder.standard()
                .withEndpointConfiguration(endpoint).build();


        DetectDocumentTextRequest request = new DetectDocumentTextRequest()
            .withDocument(new Document().withS3Object(new S3Object().withName(document).withBucket(bucket)));

        DetectDocumentTextResult result = client.detectDocumentText(request);
        
        //reading text and copying it to files
        String res = "";
        for(Block block:result.getBlocks()) {
        	if(block.getBlockType().equals("LINE")) {
        		res = res + String.valueOf(block.getGeometry().getBoundingBox().getLeft())+" "+ block.getText() + "\n";
        	}
        }
        System.out.println(res);
        saveString(res);
        
    }
}