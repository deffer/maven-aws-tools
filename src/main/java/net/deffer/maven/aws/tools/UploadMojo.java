package net.deffer.maven.aws.tools;

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

/**
 * Uploads files/folders to S3 bucket.
 *
 * Supports:
 * Uploading 1 file (destination name can be changed)
 * Uploading list of files (destination name is interpreted as "subfolder" in S3)
 * Uploading folder(s) (destination name is interpreted as "subfolder" in S3)
 *
 * Limitations:
 * Only one bucket can be configured per execution.
 * No metadata support.
 * No ACL support.
 * When uploading  more than 1 file/folder, destination names of files will be the same as original names.
 *
 * author: Irina Benediktovich - http://plus.google.com/+IrinaBenediktovich
 */
@Mojo(name = "upload")
public class UploadMojo extends AbstractMojo {

    /**
     * Lists mechanisms of providing AWS credentials to java sdk.
     * ENV - through environment variables
     * JAVA - through java variables
     * FILE - through credentials file (USER_HOME/.aws/credentials)
     * INSTANCE - when running on EC2
     * PROVIDED - configured in pom
     */
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

    /**
     * env, java, file (default), instance, provided
     * @see net.deffer.maven.aws.tools.UploadMojo.CRED_TYPES
     */
	@Parameter(property = "run.credentialProvider", defaultValue = "file")
	private String credProviderParam;

    /**
     * Only used if credProviderParam == PROVIDED
     */
	@Parameter(property = "run.accessKey", required = false)
	private String accessKey;

    /**
     * Only used if credProviderParam == PROVIDED
     */
	@Parameter(property = "run.secretKey", required = false)
	private String secretKey;

    /**
     * Report upload plan, but dont do actual upload
     */
	@Parameter(property = "run.dryRun", defaultValue = "false")
	private boolean dryRun;

    /**
     * Destination S3 bucket name
     */
    @Parameter(property = "run.bucketName", required = true)
    private String bucketName;

    /**
     * File/folder name. Only use when there is only file/folder to upload.
     */
	@Parameter(property = "run.source", required = false)
	private String source; // The file to upload

    /**
     * List of files/folders to upload.
     */
    @Parameter(property = "run.source", required = false)
    private String[] sources; // Or files

    /**
     * If there is only one file and its passed as source, destination means new file name (on S3).
     * Otherwise its a name of the "subfolder" in the bucket where all files/folders will go to.
     */
	@Parameter(property = "run.destination", required = false, defaultValue = "")
	private String destination; // Name of the object in S3 (if only one source) or folder prefix in S3

    /**
     * Added to destination file names (for instance if suffix="-1.1" and source is "castle", final name is "castle-1.1"
     * Only works for source files (not folder and not files in those folders)
     */
    @Parameter(property = "run.suffix", required = false, defaultValue = "")
    private String suffix; // added to the file names in S3, can be used for versioning of files

    /**
     * When uploading folders, whether subfolders should be uploaded too.
     */
    @Parameter(property = "run.recursive", defaultValue = "false")
    private boolean recursive; // how to upload folders, if there are any


    /**
     * main method. does everything.
     *
     * @throws MojoExecutionException
     */
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
                    upload = tm.uploadDirectory(bucketName, destination, sourceFile, true);

                try {
                    getLog().debug("Transferring " + upload.getProgress().getTotalBytesToTransfer() + " bytes...");

                    upload.waitForCompletion();  // <----------- and wait --
                    if (isFile)
                        getLog().info("Upload complete. " + upload.getProgress().getBytesTransferred() + " bytes. "+sourceFileName+" to "+ bucketName + " as "+key);
                    else
                        getLog().info("Upload complete: folder " + sourceFileName+" to "+ bucketName + (destination.isEmpty()?"":" as "+destination));
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
	            if (isFile)
                    getLog().info("(dry run) File "+sourceFileName+" would have been uploaded to "+bucketName+" as "+key);
	            else
                    getLog().info("(dry run) Folder "+sourceFileName+" would have been uploaded to "+bucketName+ (destination.isEmpty()?"":" as "+destination));
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
