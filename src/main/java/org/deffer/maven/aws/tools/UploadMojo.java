package org.deffer.maven.aws.tools;

import java.io.File;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.*;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
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

	// env, java, file (default), instance
	@Parameter(property = "run.credentialProvider", defaultValue = "file")
	private String credProviderParam;

	@Parameter(property = "run.accessKey")
	private String accessKey;

	@Parameter(property = "run.secretKey")
	private String secretKey;

	@Parameter(property = "run.dryRun", defaultValue = "false")
	private boolean dryRun;

	// The file to upload
	@Parameter(property = "run.source", required = true)
	private String source;

	// Name of the object in S3. If empty, same as source file name
	@Parameter(property = "run.destination", required = false)
	private String destination;

	@Parameter(property = "run.bucketName", required = true)
	private String bucketName;



	@Override
	public void execute() throws MojoExecutionException {

		File sourceFile = new File(source);
		if (!sourceFile.exists())
			throw new MojoExecutionException("File doesn't exist: " + source);
		if (!sourceFile.isFile())
			throw new MojoExecutionException("Folder upload is not supported " + sourceFile);

		AWSCredentialsProvider credProvider = getProvider(CRED_TYPES.fromString(credProviderParam));
		TransferManager tm = new TransferManager(credProvider);

		Upload upload = tm.upload(bucketName, destination, sourceFile);   // <----------- UPLOAD --

		try {
			getLog().debug("Transferring " + upload.getProgress().getTotalBytesToTransfer() + " bytes...");

			// block and wait for the upload to finish
			upload.waitForCompletion();  // <----------- and wait --

			getLog().debug("Upload complete. " + upload.getProgress().getBytesTransferred() + " bytes.");
		} catch (AmazonClientException ae) {
			getLog().error("Unable to upload file, upload was aborted. "+ae.getMessage(), ae);
			ae.printStackTrace();
			tm.shutdownNow();
			throw new MojoExecutionException("Unable to upload file to S3: "+ae.getMessage(), ae);
		}  catch (InterruptedException e) {
			getLog().error("Unable to upload file to S3: unexpected interruption");
			tm.shutdownNow();
			throw new MojoExecutionException("Unable to upload file to S3: unexpected interruption");
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
