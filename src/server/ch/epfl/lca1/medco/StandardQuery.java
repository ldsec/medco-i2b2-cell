/*
 * Copyright (c) 2006-2007 Massachusetts General Hospital
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the i2b2 Software License v1.0
 * which accompanies this distribution.
 *
 * Contributors:
 *     Rajesh Kuttan
 */
package ch.epfl.lca1.medco;

import ch.epfl.lca1.medco.i2b2.crc.I2B2CRCCell;
import ch.epfl.lca1.medco.i2b2.crc.I2B2QueryRequest;
import ch.epfl.lca1.medco.i2b2.crc.I2B2QueryResponse;
import ch.epfl.lca1.medco.i2b2.pm.I2B2PMCell;
import ch.epfl.lca1.medco.i2b2.pm.UserInformation;
import ch.epfl.lca1.medco.unlynx.UnlynxClient;
import ch.epfl.lca1.medco.util.Constants;
import ch.epfl.lca1.medco.util.Logger;
import ch.epfl.lca1.medco.util.Timers;
import ch.epfl.lca1.medco.util.exceptions.MedCoError;
import ch.epfl.lca1.medco.util.exceptions.MedCoException;
import edu.harvard.i2b2.common.exception.I2B2Exception;
import edu.harvard.i2b2.common.util.jaxb.JAXBUtilException;
import edu.harvard.i2b2.crc.datavo.setfinder.query.PanelType;
import edu.harvard.i2b2.crc.datavo.setfinder.query.QueryDefinitionType;
import org.javatuples.Pair;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

//

//todo doc: https://github.com/chb/shrine/tree/master/doc

/**
 * Represents a query to MedCo.
 * From the XML query (in CRC format), parse to extract the sensitive attributes, 
 * make query to CRC for non-sensitive attributes, get the patient set from CRC,
 * query the cothority with the patient sets and sensitive attributes and answer.
 *
 *
 * everything under that sohuld not use the config!!
 */
public class StandardQuery {

    private I2B2QueryRequest queryRequest;
    private I2B2CRCCell crcCell;
    private I2B2PMCell pmCell;
    private UnlynxClient unlynxClient;



    //int resultMode, String clientPubKey, long timoutSeconds
	public StandardQuery(I2B2QueryRequest request,
                         String unlynxBinPath, String unlynxGroupFilePath, int unlynxDebugLevel, int unlynxEntryPointIdx,
                         int unlynxProofsFlag, long unlynxTimeoutSeconds,
                         String crcCellUrl, String pmCellUrl) throws I2B2Exception {
		this.queryRequest = request;
		unlynxClient = new UnlynxClient(unlynxBinPath, unlynxGroupFilePath, unlynxDebugLevel, unlynxEntryPointIdx, unlynxProofsFlag, unlynxTimeoutSeconds);
		crcCell = new I2B2CRCCell(crcCellUrl, queryRequest.getMessageHeader());
		pmCell = new I2B2PMCell(pmCellUrl, queryRequest.getMessageHeader());
	}
	
	/**
	 * 
	 * @return the query answer in CRC XML format.
	 * @throws JAXBUtilException 
	 */
	public I2B2QueryResponse executeQuery() throws MedCoException, I2B2Exception {
	    Timers.resetTimers();
	    Timers.get("overall").start();

	    // get user information (auth., privacy budget, authorizations, public key)
        // todo: get and check budget query / user
        // todo: get user permissions
        Timers.get("steps").start("User information retrieval");
        UserInformation user = pmCell.getUserInformation(queryRequest.getMessageHeader());
        if (!user.isAuthenticated()) {
            Logger.warn("Authentication failed for user " + user.getUsername());
            // todo: proper auth failed response
            return null;
        }
        QueryType queryType = QueryType.resolveUserPermission(user.getRoles());
        Timers.get("steps").stop();

        // retrieve the encrypted query terms
        Timers.get("steps").start("Query parsing/splitting");
        List<String> encryptedQueryItems = extractEncryptedQueryTerms();
        Timers.get("steps").stop();

        // intercept test query from SHRINE and bypass unlynx
        if (encryptedQueryItems.contains(Constants.CONCEPT_NAME_TEST_FLAG)) {
            Logger.info("Intercepted SHRINE status query (" + queryRequest.getQueryName() + ").");
            replaceEncryptedQueryTerms(encryptedQueryItems);
            return crcCell.queryRequest(queryRequest);
        }

        // query unlynx to tag the query terms
        Timers.get("steps").start("Query tagging");
        List<String> taggedItems = unlynxClient.computeDistributedDetTags(queryRequest.getQueryName(), encryptedQueryItems);
        Timers.addAdditionalTimes(unlynxClient.getLastTimingMeasurements());
        Timers.get("steps").stop();

        // replace the query terms, query i2b2 with the original clear query terms + the tagged ones
        Timers.get("steps").start("i2b2 query");
        replaceEncryptedQueryTerms(taggedItems);
        overrideResultOutputTypes(new String[]{"PATIENTSET", "PATIENT_COUNT_XML"});
        I2B2QueryResponse i2b2Response = crcCell.queryRequest(queryRequest);
        Timers.get("steps").stop();

        // retrieve the patient set, including the encrypted dummy flags
        Timers.get("steps").start("i2b2 patient set retrieval");
        Pair<List<String>, List<String>> patientSet = crcCell.queryForPatientSet(i2b2Response.getPatientSetId(), true);
        Timers.get("steps").stop();

        String aggResult;
        switch (queryType) {

            case AGGREGATED_PER_SITE:
                aggResult = unlynxClient.aggregateData(queryRequest.getQueryName(), user.getUserPublicKey(), patientSet.getValue1());
                Timers.addAdditionalTimes(unlynxClient.getLastTimingMeasurements());

                break;

            case OBFUSCATED_PER_SITE:
            case AGGREGATED_TOTAL:
            default:
                throw new MedCoError("Query type not supported yet.");

        }

        i2b2Response.resetResultInstanceListToEncryptedCountOnly();
        Timers.get("overall").stop();
        i2b2Response.setQueryResults(user.getUserPublicKey(), aggResult, Timers.generateFullReport());

        Logger.info("MedCo query successful (" + queryRequest.getQueryName() + ").");
        return i2b2Response;
		
	}


    /**
     * TODO
     * No checks on panels are done (i.e. if they contain mixed query types or not)
     *
     * @param taggedItems
     * @throws MedCoException
     */
    private void replaceEncryptedQueryTerms(List<String> taggedItems) throws MedCoException {
        QueryDefinitionType qd = queryRequest.getQueryDefinition();
        int encTermCount = 0;

        // iter on the panels
        for (int p = 0; p < qd.getPanel().size(); p++) {
            PanelType panel = qd.getPanel().get(p);

            // iter on the items
            int nbItems = panel.getItem().size();
            for (int i = 0; i < nbItems; i++) {

                // replace encrypted item with its tagged version
                Matcher medcoKeyMatcher = Constants.REGEX_QUERY_KEY_ENC.matcher(panel.getItem().get(i).getItemKey());
                if (medcoKeyMatcher.matches()) {
                    panel.getItem().get(i).setItemKey(Constants.CONCEPT_PATH_TAGGED_PREFIX + taggedItems.get(encTermCount++) + "\\");
                }

            }
        }

        // check the provided taggedItems match the number of encrypted terms
        if (encTermCount != taggedItems.size()) {
            Logger.warn("Mismatch in provided number of tagged items (" + taggedItems.size() + ") and number of encrypted items in query (" + encTermCount + ")");
        }
    }

    private void overrideResultOutputTypes(String[] outputTypes) throws MedCoException {
        queryRequest.setOutputTypes(outputTypes);
    }

    /**
     * todo: to rewrite
     * Extract from the i2b2 query the sensitive / encrypted items recognized by the prefix defined in {@link Constants}.
     * Accepts only panels fully clear or encrypted, i.e. no mix is allowed.
     * <p>
     * The predicate, if returned, has the following format:
     * (exists(v0, r) || exists(v1, r)) &amp;&amp; (exists(v2, r) || exists(v3, r)) &amp;&amp; exists(v4, r)
     *
     * @return the list of encrypted query terms and optionally the corresponding predicate
     * @throws MedCoException if a panel contains mixed clear and encrypted query terms
     */
    private List<String> extractEncryptedQueryTerms() throws MedCoException {
        // todo: handle cases: only clear no encrypt / only encrypt no clear
        // todo: must be modified if invertion implementation

        QueryDefinitionType qd = queryRequest.getQueryDefinition();
        List<String> extractedItems = new ArrayList<>();

        // iter on the panels
        for (int p = 0; p < qd.getPanel().size(); p++) {
            PanelType panel = qd.getPanel().get(p);

            // iter on the items
            int nbItems = panel.getItem().size();
            for (int i = 0; i < nbItems; i++) {

                // check if item is clear or encrypted, extract and generate predicate if yes
                Matcher medcoKeyMatcher = Constants.REGEX_QUERY_KEY_ENC.matcher(panel.getItem().get(i).getItemKey());
                if (medcoKeyMatcher.matches()) {
                    extractedItems.add(medcoKeyMatcher.group(1));
                    Logger.debug("Extracted item " + extractedItems.get(extractedItems.size() - 1) + "; panel=" + p + ", item=" + i);
                }
            }
        }

        Logger.info("Extracted " + extractedItems.size() + " encrypted query terms for query " + queryRequest.getQueryName());
        return extractedItems;
    }

}
