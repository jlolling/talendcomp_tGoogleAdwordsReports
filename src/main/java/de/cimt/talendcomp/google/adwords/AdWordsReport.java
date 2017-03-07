/**
 * Copyright 2015 Jan Lolling jan.lolling@gmail.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cimt.talendcomp.google.adwords;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Logger;

import com.google.api.ads.adwords.lib.client.AdWordsSession;
import com.google.api.ads.adwords.lib.client.reporting.ReportingConfiguration;
import com.google.api.ads.adwords.lib.jaxb.v201702.DateRange;
import com.google.api.ads.adwords.lib.jaxb.v201702.DownloadFormat;
import com.google.api.ads.adwords.lib.jaxb.v201702.ReportDefinition;
import com.google.api.ads.adwords.lib.jaxb.v201702.ReportDefinitionDateRangeType;
import com.google.api.ads.adwords.lib.jaxb.v201702.ReportDefinitionReportType;
import com.google.api.ads.adwords.lib.jaxb.v201702.Selector;
import com.google.api.ads.adwords.lib.utils.ReportDownloadResponse;
import com.google.api.ads.adwords.lib.utils.v201702.ReportDownloader;
import com.google.api.ads.common.lib.auth.OfflineCredentials;
import com.google.api.ads.common.lib.auth.OfflineCredentials.Api;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Clock;
import com.google.api.client.util.store.FileDataStoreFactory;

public class AdWordsReport {
	
	private Logger logger = null;
	private static final Map<String, AdWordsReport> clientCache = new HashMap<String, AdWordsReport>();
	private final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	private final JsonFactory JSON_FACTORY = new JacksonFactory();
	private final static String ADWORDS_SCOPE = "https://www.googleapis.com/auth/adwords";
	private File keyFile; // *.p12 key file is needed
	private String clientSecretFile = null;
	private String clientId = null;
	private String clientSecret;
	private String serviceAccountIdEmail;
	private long timeMillisOffsetToPast = 10000;
	private String userEmail = null;
	private AdWordsSession session = null;
	private boolean useServiceAccount = false;
	private boolean useClientId = false;
	private String clientCustomerId = null;
	private String refreshToken = null;
	private String userAgent = "de.jlo.talendcomp.google.adwords.AdWordsReports:V2.1";
	private String developerToken = null;
	private String adwordsPropertyFilePath = null;
	private boolean usePropertyFile = false;
	private String reportType = null;
	private String fields = null;
	private String startDateStr = null;
	private String endDateStr = null;
	private boolean useAWQL = false;
	private String awql = null;
	private int reportDownloadTimeout = 3000;
	private String downloadDir = null;
	private String reportName = null;
	private String reportDownloadFilePath = null;
	private SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd");
	private boolean debug = true;
	private ReportDefinition reportDefinition;
	private boolean deliverTotalsDataset = false;
	private DownloadFormat downloadFormat = DownloadFormat.CSV;
	private int httpStatus = 0;
	private ReportDownloadResponse response = null;
	private boolean sendReportAsAWQL = false;
	private String awqlWhereClause = null;
	private InputStream responseInputStream = null;
	
	public void reset() {
		awql = null;
		reportType = null;
		fields = null;
		startDateStr = null;
		endDateStr = null;
		useAWQL = false;
		downloadDir = null;
		reportName = null;
		reportDownloadFilePath = null;
		reportDefinition = null;
		downloadFormat = DownloadFormat.CSV;
		httpStatus = 0;
		response = null;
		awqlWhereClause = null;
		responseInputStream = null;
	}
	
	public static void putIntoCache(String key, AdWordsReport gai) {
		clientCache.put(key, gai);
	}
	
	public static AdWordsReport getFromCache(String key) {
		AdWordsReport adr = clientCache.get(key);
		if (adr != null) {
			adr.reset();
			return adr;
		} else {
			return null;
		}
	}
	
	private Credential authorizeWithServiceAccount() throws Exception {
		if (keyFile == null) {
			throw new Exception("KeyFile not set!");
		}
		if (keyFile.canRead() == false) {
			throw new IOException("keyFile:" + keyFile.getAbsolutePath()
					+ " is not readable");
		}
		if (serviceAccountIdEmail == null || serviceAccountIdEmail.isEmpty()) {
			throw new Exception("account email cannot be null or empty");
		}
		// Authorization.
		return new GoogleCredential.Builder()
				.setTransport(HTTP_TRANSPORT)
				.setJsonFactory(JSON_FACTORY)
				.setServiceAccountId(serviceAccountIdEmail)
				.setServiceAccountScopes(Arrays.asList(ADWORDS_SCOPE))
				.setServiceAccountPrivateKeyFromP12File(keyFile)
				.setServiceAccountUser(userEmail)
				.setClock(new Clock() {
					@Override
					public long currentTimeMillis() {
						// we must be sure, that we are always in the past from Googles point of view
						// otherwise we get an "invalid_grant" error
						return System.currentTimeMillis() - timeMillisOffsetToPast;
					}
				})
				.build();
	}
	
	private Credential authorizeWithClientSecretAndRefreshToken() throws Exception {
		info("Authorise with Client-ID for installed application with existing refresh token ....");
		Credential oAuth2Credential = null;
		if (usePropertyFile) {
			info("... use property file: " + adwordsPropertyFilePath);
			oAuth2Credential = new OfflineCredentials.Builder()
		        .forApi(Api.ADWORDS)
		        .fromFile(adwordsPropertyFilePath)
		        .build()
		        .generateCredential();
		} else {
			info("... set properties directly");
			if (clientId == null) {
				throw new IllegalStateException("Client-ID not set or null");
			}
			if (clientSecretFile == null) {
				throw new IllegalStateException("Client-ID not set or null");
			}
			oAuth2Credential = new OfflineCredentials.Builder()
		        .forApi(Api.ADWORDS)
		        .withClientSecrets(clientId, clientSecret)
		        .withHttpTransport(HTTP_TRANSPORT)
		        .withRefreshToken(refreshToken)
		        .build()
		        .generateCredential();
		}
		return oAuth2Credential;
	}
	
	/**
	 * Authorizes the installed application to access user's protected YouTube
	 * data.
	 * 
	 * @param scopes
	 *            list of scopes needed to access general and analytic YouTube
	 *            info.
	 */
	private Credential authorizeWithClientSecret() throws Exception {
		info("Authorise with Client-ID for installed application with using credential data store....");
		if (clientSecretFile == null) {
			throw new IllegalStateException("client secret file is not set");
		}
		File secretFile = new File(clientSecretFile);
		if (secretFile.exists() == false) {
			throw new Exception("Client secret file:" + secretFile.getAbsolutePath() + " does not exists or is not readable.");
		}
		Reader reader = new FileReader(secretFile);
		// Load client secrets.
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, reader);
		try {
			reader.close();
		} catch (Throwable e) {}
		// Checks that the defaults have been replaced (Default =
		// "Enter X here").
		if (clientSecrets
				.getDetails()
				.getClientId()
				.startsWith("Enter") || 
				clientSecrets
						.getDetails()
						.getClientSecret()
						.startsWith("Enter ")) {
			throw new Exception("The client secret file does not contains the credentials!");
		}
		String credentialDataStoreDir = secretFile.getParent() + "/" + clientSecrets.getDetails().getClientId() + "/";
		File credentialDataStoreDirFile = new File(credentialDataStoreDir);             
		if (credentialDataStoreDirFile.exists() == false && credentialDataStoreDirFile.mkdirs() == false) {
			throw new Exception("Credentedial data dir does not exists or cannot created:" + credentialDataStoreDir);
		}
		if (debug) {
			info("Credential data store dir:" + credentialDataStoreDir);
		}
		FileDataStoreFactory fdsf = new FileDataStoreFactory(credentialDataStoreDirFile);
		// Set up authorization code flow.
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
					HTTP_TRANSPORT, 
					JSON_FACTORY, 
					clientSecrets, 
					Arrays.asList(ADWORDS_SCOPE))
				.setDataStoreFactory(fdsf)
				.setClock(new Clock() {
					@Override
					public long currentTimeMillis() {
						// we must be sure, that we are always in the past from Googles point of view
						// otherwise we get an "invalid_grant" error
						return System.currentTimeMillis() - timeMillisOffsetToPast;
					}
				})
				.build();
		// Authorize.
		return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize(userEmail);
	}

	public void initializeAdWordsSession() throws Exception {
		Credential oAuth2Credential = null;
		if (useServiceAccount) {
			oAuth2Credential = authorizeWithServiceAccount();
		} else if (useClientId) {
			if (refreshToken != null || usePropertyFile) {
				oAuth2Credential = authorizeWithClientSecretAndRefreshToken();
			} else {
				oAuth2Credential = authorizeWithClientSecret();
			}
		} else {
			throw new IllegalStateException("Not authorized. Please choose an authorization method!");
		}
		if (oAuth2Credential == null) {
			error("Authentication failed. Check the Exception thrown before.", null);
			return;
		}
		oAuth2Credential.refreshToken();
		if (usePropertyFile) {
			session = new AdWordsSession.Builder()
				.fromFile(adwordsPropertyFilePath)
		        .withOAuth2Credential(oAuth2Credential)
		        .build();
		} else {
			if (clientCustomerId == null) {
				throw new IllegalStateException("clientCustomerId mus be set");
			}
			session = new AdWordsSession.Builder()
				.withClientCustomerId(clientCustomerId)
				.withDeveloperToken(developerToken)
				.withUserAgent(userAgent)
		        .withOAuth2Credential(oAuth2Credential)
		        .build();
		}
	}
	
	public void executeQuery() throws Exception {
	    ReportingConfiguration reportingConfiguration = new ReportingConfiguration.Builder()
	        .skipReportHeader(true)
	        .skipReportSummary(!deliverTotalsDataset)
	        .build();
	    session.setReportingConfiguration(reportingConfiguration);
	    // because we do not change anything in the configuration
	    session.setValidateOnly(true);
		ReportDownloader downloader = new ReportDownloader(session);
		downloader.setReportDownloadTimeout(reportDownloadTimeout);
		if (sendReportAsAWQL && useAWQL == false) {
			if (fields == null) {
				throw new IllegalStateException("No fields has been set!");
			}
			if (reportType == null) {
				throw new IllegalStateException("No reportType has been set!");
			}
			// build AWQL from fields and reportType to allow the where syntax in the 
			// report definition configuration
			buildAWQLFromReportDefinition();
		}
		if (sendReportAsAWQL || useAWQL) {
			if (awql == null) {
				throw new IllegalStateException("No AWQL has been set!");
			}
			// we need the report type set build the download file name
			if (sendReportAsAWQL == false) {
				// if we create the AWQL for our self, we do not need to parse it
				AWQLParser parser = new AWQLParser();
				parser.parse(awql);
				reportType = parser.getReportType();
				fields = parser.getFieldsAsString();
			}
			// use AdWords query language
			String awqlRequestFormat = AWQLParser.buildRequestFormat(awql);
			if (debug) {
				info("AWQL request formatted:" + awqlRequestFormat);
			}
			// we have to setup the report name here because we need the reportType to do so.
			setupReportName();
			response = downloader.downloadReport(awqlRequestFormat, downloadFormat);
		} else {
			// define the report from given fields + predicates
			setupReportName();
			setupReportDefinition();
			response = downloader.downloadReport(reportDefinition);
		}
		responseInputStream = response.getInputStream();
	}
	
	public void downloadAsFile() throws Exception {
		buildDownloadFile();
		info("Download report to: " + reportDownloadFilePath);
		httpStatus = response.getHttpStatus();
		response.saveToFile(reportDownloadFilePath);
		info("Finished.");
	}

	private File buildDownloadFile() {
		if (reportDownloadFilePath != null) {
			File df = new File(reportDownloadFilePath);
			if (df.getParentFile().exists() == false) {
				df.getParentFile().mkdirs();
			}
			return df;
		} else {
			if (downloadDir == null) {
				throw new IllegalStateException("Download dir not set!");
			}
			if (reportType == null) {
				throw new IllegalStateException("Report-Type not set!");
			}
			if (reportName == null) {
				throw new IllegalStateException("Report-Name not set!");
			}
			if (downloadFormat == null) {
				throw new IllegalStateException("Download format not set!");
			}
			String fileExtension = null;
			if (downloadFormat == DownloadFormat.CSV) {
				fileExtension = ".csv";
			} else if (downloadFormat == DownloadFormat.CSVFOREXCEL) {
				fileExtension = ".bom.csv";
			} else if (downloadFormat == DownloadFormat.TSV) {
				fileExtension = ".tsv";
			} else if (downloadFormat == DownloadFormat.XML) {
				fileExtension = ".xml";
			} else if (downloadFormat == DownloadFormat.GZIPPED_CSV) {
				fileExtension = ".csv.gz";
			} else if (downloadFormat == DownloadFormat.GZIPPED_XML) {
				fileExtension = ".xml.gz";
			}
			File df = new File(downloadDir, reportName + fileExtension);
			if (df.getParentFile().exists() == false) {
				df.getParentFile().mkdirs();
			}
			reportDownloadFilePath = df.getAbsolutePath();
			return df;
		}
	}
	
	private void buildAWQLFromReportDefinition() {
		StringBuilder sb = new StringBuilder();
		sb.append("select ");
		// add fields
		sb.append(fields);
		sb.append(" from ");
		sb.append(reportType);
		if (awqlWhereClause != null) {
			sb.append(" where ");
			sb.append(awqlWhereClause);
		}
		sb.append(" during ");
		sb.append(startDateStr);
		sb.append(",");
		sb.append(endDateStr);
		awql = sb.toString();
	}
	
	private ReportDefinition setupReportDefinition() {
	    if (isEmpty(startDateStr)) {
	    	throw new IllegalStateException("Start date is not set");
	    }
	    if (isEmpty(endDateStr)) {
	    	throw new IllegalStateException("End date is not set");
	    }
	    Selector selector = new Selector();
	    selector.getFields().addAll(buildFieldList());
	    DateRange dr = new DateRange();
	    dr.setMin(startDateStr);
	    dr.setMax(endDateStr);
	    selector.setDateRange(dr);
	    reportDefinition = new ReportDefinition();
		reportDefinition.setSelector(selector);
		reportDefinition.setReportType(getReportDefinitionType());
		reportDefinition.setReportName(reportName);
		reportDefinition.setDateRangeType(ReportDefinitionDateRangeType.fromValue("CUSTOM_DATE"));
		reportDefinition.setDownloadFormat(downloadFormat);
		return reportDefinition;
	}
	
	private String setupReportName() {
		if (reportType == null) {
			throw new IllegalStateException("reportType cannot be null");
		}
		if (reportName != null) {
			return reportName;
		} else {
			if (awql != null) {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS");
				reportName = reportType + "#" + sdf.format(new Date());
			} else {
				reportName = reportType + "#" + startDateStr + "-" + endDateStr;
			}
			return reportName;
		}
	}
	
	private ReportDefinitionReportType getReportDefinitionType() {
		if (reportType == null) {
			throw new IllegalStateException("The report-type must be set!");
		}
		return ReportDefinitionReportType.fromValue(reportType);
	}
	
	private List<String> buildFieldList() {
		if (fields == null) {
			throw new IllegalStateException("Fields cannot be empty. Please specify the report fields!");
		}
		List<String> fieldList = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(fields, ",;|");
		while (st.hasMoreTokens()) {
			String field = st.nextToken().trim();
			fieldList.add(field);
		}
		return fieldList;
	}

	public void setFields(String fields) {
		if (isEmpty(fields) == false) {
			this.fields = fields;
		}
	}

	public void setStartDate(String startDate) {
		if (isEmpty(startDate) == false) {
			this.startDateStr = checkDate(startDate);
		}
	}
	
	public void setStartDate(Date startDate) {
		if (startDate != null) {
			this.startDateStr = sdfDate.format(startDate);
		}
	}
	
	public void setEndDate(String endDate) {
		if (isEmpty(endDate) == false) {
			this.endDateStr = checkDate(endDate);
		}
	}

	public void setEndDate(Date endDate) {
		if (endDate != null) {
			this.endDateStr = sdfDate.format(endDate);
		}
	}

	private String checkDate(String dateStr) {
		if (isEmpty(dateStr)) {
			throw new IllegalArgumentException("the given date is empty or null");
		}
		dateStr = dateStr.replace("-", "");
		return dateStr;
	}

	public String getReportDownloadFilePath() {
		return reportDownloadFilePath;
	}

	public void setReportDownloadFilePath(String reportDownloadFilePath) {
		if (isEmpty(reportDownloadFilePath) == false) {
			this.reportDownloadFilePath = reportDownloadFilePath;
		}
	}
	
	public static boolean isEmpty(String s) {
		return s == null || s.trim().isEmpty();
	}

	public void setReportType(String reportType) {
		if (isEmpty(reportType) == false) {
			this.reportType = reportType;
		}
	}

	public void setDeveloperToken(String developerToken) {
		if (isEmpty(developerToken) == false) {
			this.developerToken = developerToken;
		}
	}

	public void setUserAgent(String userAgent) {
		if (isEmpty(userAgent) == false) {
			this.userAgent = userAgent;
		}
	}

	public void setClientCustomerId(String clientCustomerId) {
		if (isEmpty(clientCustomerId) == false) {
			this.clientCustomerId = clientCustomerId;
		}
	}

	public void setClientId(String clientId) {
		if (isEmpty(clientId) == false) {
			this.clientId = clientId;
		}
	}

	public void setClientSecretFile(String clientSecretFile) {
		if (isEmpty(clientSecretFile) == false) {
			this.clientSecretFile = clientSecretFile;
		}
	}

	public void setKeyFile(String keyFileStr) {
		if (isEmpty(keyFileStr) == false) {
			File f = new File(keyFileStr);
			if (f.canRead() == false) {
				throw new IllegalArgumentException("Key file:" + keyFileStr + " cannot be read!");
			}
			this.keyFile = f;
		}
	}

	public void setServiceAccountIdEmail(String accountEmail) {
		if (isEmpty(accountEmail) == false) {
			this.serviceAccountIdEmail = accountEmail;
		}
	}

	public void setUserEmail(String adWordsAccountEmail) {
		if (isEmpty(adWordsAccountEmail) == false) {
			this.userEmail = adWordsAccountEmail;
		}
	}

	public void setUseServiceAccount(boolean useServiceAccount) {
		this.useServiceAccount = useServiceAccount;
	}

	public void setAdwordsPropertyFilePath(String adwordsPropertyFilePath) {
		if (isEmpty(adwordsPropertyFilePath) == false) {
			this.adwordsPropertyFilePath = adwordsPropertyFilePath;
		}
	}

	public void setUsePropertyFile(boolean usePropertyFile) {
		this.usePropertyFile = usePropertyFile;
	}

	public void setAwql(String awql) {
		if (isEmpty(awql) == false) {
			this.awql = awql.trim();
		}
	}

	public void setDownloadDir(String downloadDir) {
		if (isEmpty(downloadDir) == false) {
			this.downloadDir = downloadDir;
		}
	}

	public void setUseClientId(boolean useClientId) {
		this.useClientId = useClientId;
	}

	public void setRefreshToken(String refreshToken) {
		if (isEmpty(refreshToken) == false) {
			this.refreshToken = refreshToken;
		}
	}

	public void setClientSecret(String clientSecret) {
		if (isEmpty(clientSecret) == false) {
			this.clientSecret = clientSecret;
		}
	}

	public String getReportName() {
		return reportName;
	}

	public void setReportName(String reportName) {
		if (isEmpty(reportName) == false) {
			this.reportName = reportName;
		}
	}

	public void deliverTotalsDataset(boolean deliverTotalsDataset) {
		this.deliverTotalsDataset = deliverTotalsDataset;
	}

	public void setTimeMillisOffsetToPast(Long timeMillisOffsetToPast) {
		if (timeMillisOffsetToPast != null) {
			this.timeMillisOffsetToPast = timeMillisOffsetToPast.longValue();
		}
	}

	public void setTimeMillisOffsetToPast(Integer timeMillisOffsetToPast) {
		if (timeMillisOffsetToPast != null) {
			this.timeMillisOffsetToPast = timeMillisOffsetToPast.longValue();
		}
	}

	public String getReportType() {
		return reportType;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}
	
	/**
	 * set the format of the downloaded file
	 * Following formats are allowed:
	 * CSV,XML,CSVFOREXCEL,TSV,GZIPPED_CSV,GZIPPED_XML
	 * @param format
	 */
	public void setDownloadFormat(String format) {
		if ("CSV".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.CSV;
		} else if ("XML".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.XML;
		} else if ("TSV".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.TSV;
		} else if ("GZIPPED_CSV".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.GZIPPED_CSV;
		} else if ("GZIPPED_XML".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.GZIPPED_XML;
		} else if ("CSVFOREXCEL".equalsIgnoreCase(format)) {
			downloadFormat = DownloadFormat.CSVFOREXCEL;
		} else {
			throw new IllegalArgumentException("Download format:" + format + " is not supported!");
		}
	}
	
	public boolean downloadIsAnArchive() {
		return (downloadFormat == DownloadFormat.GZIPPED_CSV) || (downloadFormat == DownloadFormat.GZIPPED_XML);
	}

	public int getHttpStatus() {
		return httpStatus;
	}
	
	public static String unzip(String gzFilePath, boolean removeArchive) throws IOException {
		File archive = new File(gzFilePath);
		if (archive.canRead() == false) {
			throw new IOException("Archive file: " + gzFilePath + " cannot be read or does not exist.");
		}
		String archiveFileName = archive.getName();
		String targetFileName = archiveFileName.substring(0, archiveFileName.length() - ".gz".length());
		File targetFile = new File(archive.getParent(), targetFileName);
		InputStream in = new GZIPInputStream(new FileInputStream(archive));
		FileOutputStream out = new FileOutputStream(targetFile);
		try {
			byte[] buffer = new byte[1024];
			for (int c = in.read(buffer); c > -1; c = in.read(buffer)) {
				out.write(buffer, 0, c);
			}
		} finally {
			if (in != null) {
				in.close();
			}
			if (out != null) {
				out.close();
			}
		}
		if (removeArchive) {
			archive.delete();
		}
		return targetFile.getAbsolutePath();
	}

	public void info(String message) {
		if (logger != null) {
			logger.info(message);
		} else {
			System.out.println("INFO:" + message);
		}
	}
	
	public void debug(String message) {
		if (logger != null) {
			logger.debug(message);
		} else {
			System.out.println("DEBUG:" + message);
		}
	}

	public void warn(String message) {
		if (logger != null) {
			logger.warn(message);
		} else {
			System.err.println("WARN:" + message);
		}
	}

	public void error(String message, Exception e) {
		if (logger != null) {
			logger.error(message, e);
		} else {
			System.err.println("ERROR:" + message);
		}
	}

	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	public void sendReportAsAWQL(boolean createAWQL) {
		this.sendReportAsAWQL = createAWQL;
	}
	
	public void setAWQLWhereClause(String awqlWhereClause) {
		if (isEmpty(awqlWhereClause) == false) {
			this.awqlWhereClause = awqlWhereClause;
		}
	}

	public boolean isUseAWQL() {
		return useAWQL;
	}

	public void setUseAWQL(boolean useAWQL) {
		this.useAWQL = useAWQL;
	}

	public InputStream getResponseInputStream() {
		return responseInputStream;
	}

	public String getFields() {
		return fields;
	}

}
