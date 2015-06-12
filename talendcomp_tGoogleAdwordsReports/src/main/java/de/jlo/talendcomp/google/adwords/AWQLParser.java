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
package de.jlo.talendcomp.google.adwords;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class AWQLParser {
	
	private String awql = null;
	private String fields = null;
	private List<String> fieldList = null;
	private String reportType = null;
	private static final String SELECT = "select";
	private static final String FROM = "from";
	private static final String WHERE = "where";
	private static final String DURING = "during";
	
	public AWQLParser parse(String awql) {
		this.awql = awql.trim();
		findFields();
		findReportType();
		return this;
	}

	private void findReportType() {
		// find the from part
		int pos0 = awql.toLowerCase().indexOf(FROM);
		if (pos0 == -1) {
			throw new IllegalStateException("The given AWQL does not contains a \"from\" clause. The \"from\" clause is mandatory in AdHock reports!");
		}
		pos0 = pos0 + FROM.length();
		// find the where part (could be fail)
		int pos1 = awql.toLowerCase().indexOf(WHERE);
		if (pos1 == -1) {
			// where part os optional, check the during part which is not optional
			pos1 = awql.toLowerCase().indexOf(DURING);
		}
		if (pos1 == -1) {
			throw new IllegalStateException("The given AWQL does not contains a \"during\" clause. The \"during\" clause is mandatory in AdHock reports!");
		}
		reportType = awql.substring(pos0, pos1).trim();
		if (reportType.isEmpty()) {
			throw new IllegalStateException("The given AWQL does not contains a report-type in the \"from\" clause!");
		}
	}
	
	public static String buildRequestFormat(String awql) {
		// remove all line breaks
		awql = awql.replace('\n', ' ');
		awql = awql.replace('\r', ' ');
		awql = awql.replace('\t', ' ');
		awql = reduceMultipleSpacesToOne(awql);
		awql = awql.replace(", ", ",");
		awql = awql.replace(" ,", ",");
		return awql;
	}

    private static String reduceMultipleSpacesToOne(String text) {
    	text = text.trim();
    	int pos = 0;
    	while (pos != -1) {
        	text = text.replace("  ", " ");
    		pos = text.indexOf("  ");
    	}
    	return text;
    }

    private void findFields() {
		// find the from part
		int pos0 = awql.toLowerCase().indexOf(SELECT);
		if (pos0 == -1) {
			throw new IllegalStateException("The given AWQL does not contains a \"select\" clause. The \"select\" clause is mandatory in AdHock reports!");
		}
		pos0 = pos0 + SELECT.length();
		// find the where part (could be fail)
		int pos1 = awql.toLowerCase().indexOf(FROM);
		if (pos1 == -1) {
			throw new IllegalStateException("The given AWQL does not contains a \"from\" clause. The \"from\" clause is mandatory in AdHock reports!");
		}
		fields = awql.substring(pos0, pos1).trim();
		if (fields.isEmpty()) {
			throw new IllegalStateException("The given AWQL does not contains fields in the \"select\" clause!");
		}
		buildFieldList();
	}

	private void buildFieldList() {
		fieldList = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(fields, ",");
		while (st.hasMoreTokens()) {
			String field = st.nextToken().trim();
			fieldList.add(field);
		}
	}

	public String getReportType() {
		return reportType;
	}
	
	public String getFieldsAsString() {
		return fields;
	}
	
	public List<String> getFieldsAsList() {
		return fieldList;
	}
	
	
}
