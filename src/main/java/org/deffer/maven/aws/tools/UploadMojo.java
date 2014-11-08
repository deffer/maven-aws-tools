package org.deffer.maven.aws.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.*;
import com.amazonaws.auth.policy.conditions.StringCondition;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.s3.transfer.Transfer;
import com.amazonaws.services.s3.transfer.Upload;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.amazonaws.internal.StaticCredentialsProvider;
import com.amazonaws.services.s3.transfer.TransferManager;

@Mojo(name = "upload")
public class UploadMojo extends AbstractMojo {

	public static enum CRED_TYPES{
		ENV,JAVA,FILE,INSTANCE,PROVIDED;

		public static CRED_TYPES fromString(String str){
			for (CRED_TYPES c: values()){
				if (c.toString().equalsIgnoreCase(str))
					return c;
			}
			return null;
		}
	}

	@Parameter(property = "run.credentialProvider", defaultValue = "file")
	private String credProviderParam; 	// env, java, file (default), instance, provided

	@Parameter(property = "run.accessKey", required = false)
	private String accessKey;

	@Parameter(property = "run.secretKey", required = false)
	private String secretKey;

	@Parameter(property = "run.dryRun", defaultValue = "false")
	private boolean dryRun;

    @Parameter(property = "run.bucketName", required = true)
    private String bucketName;

	@Parameter(property = "run.source", required = false)
	private String source; // The file to upload

    @Parameter(property = "run.source", required = false)
    private String[] sources; // Or files

	@Parameter(property = "run.destination", required = false, defaultValue = "")
	private String destination; // Name of the object in S3 (if only one source) or folder prefix in S3

    @Parameter(property = "run.suffix", required = false, defaultValue = "")
    private String suffix; // added to the file names in S3, can be used for versioning of files

    @Parameter(property = "run.recursive", defaultValue = "false")
    private boolean recursive; // how to upload folders, if there are any


	@Override
	public void execute() throws MojoExecutionException {
        if (suffix == null) suffix = "";
        if (destination == null) destination = "";

        List<String> fileNames = new ArrayList<String>();
        if (source!=null)
            fileNames.add(source);
        if (sources!=null)
            fileNames.addAll(Arrays.asList(sources));

        if (fileNames.size() == 0){
            throw new MojoExecutionException("At least one file/folder should be specified");
        }

        for (String sourceFileName : fileNames){
            File sourceFile = new File(sourceFileName);
            if (!sourceFile.exists())
                throw new MojoExecutionException("File doesn't exist: " + source);
        }

        AWSCredentialsProvider credProvider = getProvider(CRED_TYPES.fromString(credProviderParam));
        TransferManager tm = new TransferManager(credProvider);

        for (String sourceFileName : fileNames) {
            File sourceFile = new File(sourceFileName);

            boolean isFile = sourceFile.isFile();

            String key = sourceFileName;
            if (!destination.isEmpty() && source!= null && fileNames.size()==1 && isFile)
                key = destination;
            else {
                if (!destination.isEmpty() && !destination.endsWith("/"))
                    destination+= "/";

                if (isFile){
                    key = destination + key + suffix;
                }else if (!destination.isEmpty()){
                    key = destination;
                }
            }

            if (!dryRun) {
                Transfer upload;
                if (isFile)
                    upload = tm.upload(bucketName, key, sourceFile);   // <----------- UPLOAD --
                else
                    upload = tm.uploadDirectory(bucketName,destination, sourceFile, true);

                try {
                    getLog().debug("Transferring " + upload.getProgress().getTotalBytesToTransfer() + " bytes...");

                    upload.waitForCompletion();  // <----------- and wait --

                    getLog().info("Upload complete. " + upload.getProgress().getBytesTransferred() + " bytes.");
                } catch (AmazonClientException ae) {
                    getLog().error("Unable to upload file, upload was aborted. " + ae.getMessage(), ae);
                    ae.printStackTrace();
                    tm.shutdownNow();
                    throw new MojoExecutionException("Unable to upload file to S3: " + ae.getMessage(), ae);
                } catch (InterruptedException e) {
                    getLog().error("Unable to upload file to S3: unexpected interruption");
                    tm.shutdownNow();
                    throw new MojoExecutionException("Unable to upload file to S3: unexpected interruption");
                }
            }else{
                getLog().info("(dry run) "+(isFile?"File ":"Folder ")+sourceFile+" would have been uploaded to "+bucketName+" as "+key);
            }
        }
	}

	private AWSCredentialsProvider getProvider(CRED_TYPES credProviderType) throws MojoExecutionException {
		switch (credProviderType) {
			case FILE: return new ProfileCredentialsProvider();
			case JAVA: return new SystemPropertiesCredentialsProvider();
			case ENV : return new EnvironmentVariableCredentialsProvider();
			case INSTANCE: return new InstanceProfileCredentialsProvider();
			case PROVIDED: {
				AWSCredentialsProvider provider;
				if (accessKey != null && secretKey != null) {
					AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
					provider = new StaticCredentialsProvider(credentials);
				} else {
					provider = new DefaultAWSCredentialsProviderChain();
				}
				return provider;
			}
			default: throw new MojoExecutionException("Unknown credentials provider "+credProviderType);
		}
	}

}
