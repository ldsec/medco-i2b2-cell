package ch.epfl.lca1.medco.i2b2.crc;

import java.util.List;

import ch.epfl.lca1.medco.i2b2.I2b2Status;
import edu.harvard.i2b2.crc.datavo.i2b2message.*;
import edu.harvard.i2b2.crc.datavo.setfinder.query.*;
import ch.epfl.lca1.medco.i2b2.MessagesUtil;
import ch.epfl.lca1.medco.i2b2.pm.UserAuthentication;
import ch.epfl.lca1.medco.util.Logger;

import ch.epfl.lca1.medco.util.MedCoUtil;
import edu.harvard.i2b2.common.exception.I2B2Exception;
import edu.harvard.i2b2.common.util.jaxb.JAXBUtilException;
import edu.harvard.i2b2.crc.datavo.setfinder.query.ObjectFactory;
import org.javatuples.Triplet;

/**
 * Represents an I2B2 query request.
 * Use for server side:
 * Use for client side
 *
 * @author mickael
 *
 */
public class I2B2QueryRequest extends RequestMessageType {

    // live references to the 2 elements of the request body
	private PsmQryHeaderType queryHeader;
	private QueryDefinitionRequestType queryBody;

	private UserAuthentication authentication;

	private static final String I2B2_RESULT_OPTION_TYPE = "PATIENT_COUNT_XML";

	private static MedCoUtil util = MedCoUtil.getInstance();
	private static MessagesUtil msgUtil = util.getMsgUtil();
    private static edu.harvard.i2b2.crc.datavo.setfinder.query.ObjectFactory querySetFinderOF =
            new edu.harvard.i2b2.crc.datavo.setfinder.query.ObjectFactory();
    private static edu.harvard.i2b2.crc.datavo.i2b2message.ObjectFactory i2b2OF =
            new edu.harvard.i2b2.crc.datavo.i2b2message.ObjectFactory();

    /**
	 * Used on server-side to parse an incoming request.
     * Unmashall the query from string.
	 * Checks type is with query definition. (both in header annnnnnnd for the type of xml for qrequestbody)
	 * 
	 * @param requestString
	 * @throws JAXBUtilException if the string could not be parsed / unmashalled
	 * @throws I2B2Exception if the request is of the wrong type
	 */
	public I2B2QueryRequest(String requestString) throws I2B2Exception {

		try {
		    // JAXB parsing
            RequestMessageType parsedRequest = msgUtil.parseI2b2Request(requestString);

			authentication = new UserAuthentication(parsedRequest.getMessageHeader());
            setMessageHeader(authentication);

            setMessageBody(parsedRequest.getMessageBody());
            setRequestHeader(parsedRequest.getRequestHeader());
			queryHeader = (PsmQryHeaderType) msgUtil.getUnwrapHelper().getObjectByClass(
                    getMessageBody().getAny(), PsmQryHeaderType.class);
			queryBody = (QueryDefinitionRequestType) msgUtil.getUnwrapHelper().getObjectByClass(
                    getMessageBody().getAny(), QueryDefinitionRequestType.class);

			// check type is query request with query definition
			if (!queryHeader.getRequestType().equals(PsmRequestTypeType.CRC_QRY_RUN_QUERY_INSTANCE_FROM_QUERY_DEFINITION)) {
				throw new I2B2Exception("Only query from query definition supported, got:" + queryHeader.getRequestType());
			}
		} catch (ClassCastException | JAXBUtilException e) {
			throw Logger.error(new I2B2Exception("JAXB unwrap failed.", e));
		}
	}

    /**
     * Used on client-side to create a request.
     */
    public I2B2QueryRequest(UserAuthentication auth) {

        // authentication
        authentication = auth;
        setMessageHeader(authentication);

        // standard information generated by i2b2 webclient
        RequestHeaderType reqHeader = i2b2OF.createRequestHeaderType();
        reqHeader.setResultWaittimeMs(util.getI2b2Waittimems());
        setRequestHeader(reqHeader);

        queryHeader = querySetFinderOF.createPsmQryHeaderType();
        queryHeader.setRequestType(PsmRequestTypeType.CRC_QRY_RUN_QUERY_INSTANCE_FROM_QUERY_DEFINITION);

        UserType user = querySetFinderOF.createUserType();
        user.setGroup(authentication.getSecurity().getDomain());
        user.setLogin(authentication.getSecurity().getUsername());
        user.setValue(authentication.getSecurity().getUsername());
        queryHeader.setUser(user);

        queryHeader.setEstimatedTime(0);

        // query request = query definition (created later) + result output list
        queryBody = querySetFinderOF.createQueryDefinitionRequestType();

        // result output
        ResultOutputOptionListType outputList = querySetFinderOF.createResultOutputOptionListType();
        ResultOutputOptionType resultOption = querySetFinderOF.createResultOutputOptionType();
        resultOption.setPriorityIndex(1);
        resultOption.setName(I2B2_RESULT_OPTION_TYPE);
        outputList.getResultOutput().add(resultOption);
        queryBody.setResultOutputList(outputList);

        // create the body
        BodyType body = i2b2OF.createBodyType();
        body.getAny().add(querySetFinderOF.createPsmheader(queryHeader));
        body.getAny().add(querySetFinderOF.createRequest(queryBody));
        setMessageBody(body);
    }

    public void setQueryDefinition(String queryName, List<List<String>> itemKeys) {

        QueryDefinitionType queryDef = querySetFinderOF.createQueryDefinitionType();
        queryDef.setQueryName(queryName);
        queryDef.setQueryId(queryName);
        queryDef.setQueryDescription("Query generated by the Java client: " + queryName);
        queryDef.setSpecificityScale(0);

        int panelCount = 0;
        for (List<String> panel : itemKeys) {
            PanelType panelXml = querySetFinderOF.createPanelType();
            panelXml.setPanelNumber(++panelCount);
            panelXml.setPanelAccuracyScale(0);

            for (String itemKey : panel) {
                ItemType itemXml = querySetFinderOF.createItemType();
                itemXml.setItemKey(itemKey);
                itemXml.setItemIsSynonym(false);
                panelXml.getItem().add(itemXml);
            }

            queryDef.getPanel().add(panelXml);
        }

        queryBody.setQueryDefinition(queryDef);
    }




	/**
	 * Extracts the query definition from the request.
	 * 
	 * @return the query definition
	 * @throws JAXBUtilException
	 */
	public QueryDefinitionType getQueryDefinition() {
	    return queryBody.getQueryDefinition();
	}
	


    /**
     * Resets and sets to the provided output types the list of result output type.
     *
     * @param outputTypeNames
     */
	public void setOutputTypes(String[] outputTypeNames) {
	    List<ResultOutputOptionType> outputsList = queryBody.getResultOutputList().getResultOutput();
	    outputsList.clear();

	    for (String outputTypeName : outputTypeNames) {
            ResultOutputOptionType outputType = querySetFinderOF.createResultOutputOptionType();
            outputType.setName(outputTypeName);
            outputsList.add(outputType);
        }
    }

    public String getQueryName() {
	    return queryBody.getQueryDefinition().getQueryName();
    }

    public UserAuthentication getUserAuthentication() {
	    return authentication;
    }
}
