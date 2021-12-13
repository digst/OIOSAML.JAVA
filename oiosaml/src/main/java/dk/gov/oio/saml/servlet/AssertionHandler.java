package dk.gov.oio.saml.servlet;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import dk.gov.oio.saml.audit.AuditService;
import dk.gov.oio.saml.util.*;
import org.joda.time.DateTime;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.assertion.AssertionValidationException;
import org.opensaml.saml.saml2.core.*;

import dk.gov.oio.saml.model.NSISLevel;
import dk.gov.oio.saml.service.AssertionService;
import dk.gov.oio.saml.service.OIOSAML3Service;
import dk.gov.oio.saml.service.validation.AssertionValidationService;
import dk.gov.oio.saml.session.AssertionWrapper;
import dk.gov.oio.saml.session.AuthnRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

public class AssertionHandler extends SAMLHandler {
    private static final Logger log = LoggerFactory.getLogger(AssertionHandler.class);

    @Override
    public void handleGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException {
        throw new UnsupportedOperationException("GET not allowed");
    }

    @Override
    public void handlePost(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ExternalException, InternalException, IOException {
        HttpSession session = httpServletRequest.getSession();

        // Decode request
        MessageContext<SAMLObject> messageContext = decodePost(httpServletRequest);
        SAMLObject samlObject = messageContext.getMessage();

        // Get response object
        if (!(samlObject instanceof Response)) {
            throw new ExternalException("Saml message was not a response");
        }
        Response response = (Response) samlObject;

        // Log response
        try {
            Element element = SamlHelper.marshallObject(response);
            log.debug("Response: {}", StringUtil.elementToString(element));
        } catch (MarshallingException e) {
            log.error("Could not marshall Response for logging purposes");
        }

        // Get response status
        Status status = response.getStatus();
        String responseStatus = "";
        if (status != null) {
            StatusCode code = status.getStatusCode();
            if (code != null) {
                responseStatus += code.getValue();
            }

            StatusMessage message = status.getStatusMessage();
            if (message != null) {
                responseStatus += " " + message.getMessage();
            }
        }

        // Get instant
        DateTime issueInstant = response.getIssueInstant();
        String instant = "";
        if (issueInstant != null) {
            instant = issueInstant.toString();
        }

        // Get issuer
        String issuer = response.getIssuer() != null ? response.getIssuer().getValue() : null;

        // Log response
        log.info("Incoming Response - ID:'{}' InResponseTo:'{}' Issuer:'{}' Status:'{}' IssueInstant:'{}' Destination:'{}'", response.getID(), response.getInResponseTo(), issuer, responseStatus, instant, response.getDestination());

        // Get AuthnRequest with matching ID (inResponseTo)
        AuthnRequestWrapper authnRequest = (AuthnRequestWrapper) session.getAttribute(Constants.SESSION_AUTHN_REQUEST);

        if (authnRequest == null) {
            throw new InternalException("No AuthnRequest found on session");
        }

        // Get assertion
        AssertionService assertionService = new AssertionService();
        Assertion assertion = assertionService.getAssertion(response);

        // Audit log builder
        AuditService.Builder auditBuilder = AuditRequestUtil
                .createBasicAuditBuilder(httpServletRequest, "BSA6", "ValidateAssertion")
                .withAuthnAttribute("AUTHN_REQUEST_ID", authnRequest.getId())
                .withAuthnAttribute("RESPONSE_ID", response.getID())
                .withAuthnAttribute("ASSERTION_ID", assertion.getID())
                .withAuthnAttribute("IN_RESPONSE_TO", response.getInResponseTo())
                .withAuthnAttribute("RESPONSE_STATUS", responseStatus)
                .withAuthnAttribute("SESSION_INDEX", getSessionIndex(assertion))
                .withAuthnAttribute("ISSUER", issuer)
                .withAuthnAttribute("ISSUE_INSTANT", instant)
                .withAuthnAttribute("DESTINATION", response.getDestination());

        // Validate
        AssertionWrapper wrapper;
        try {
            AssertionValidationService validationService = new AssertionValidationService();
            validationService.validate(httpServletRequest, messageContext, response, assertion, authnRequest);

            if (assertion.getAttributeStatements() == null || assertion.getAttributeStatements().size() != 1) {
                throw new ExternalException("Assertion AttributeStatements were null or had more than one");
            }

            // Assertion needs to be validated before creating the wrapper
            wrapper = new AssertionWrapper(assertion);

            log.debug("Assertion: {}", wrapper);

            auditBuilder
                    .withAuthnAttribute("RESULT", "VALID")
                    .withAuthnAttribute("SIGNATURE_REFERENCE", assertion.getSignatureReferenceID())
                    .withAuthnAttribute("SIGNATURE_ENTITY", wrapper.getSigningCredentialEntityId())
                    .withAuthnAttribute("ASSURANCE_LEVEL", wrapper.getAssuranceLevel())
                    .withAuthnAttribute("NSIS_LEVEL", wrapper.getNsisLevel().getName())
                    .withAuthnAttribute("SUBJECT_NAME_ID", wrapper.getSubjectNameId());
        }
        catch (AssertionValidationException e) {
            log.info("Failed validating assertion: {}",new AssertionWrapper(assertion).toString());
            auditBuilder.withAuthnAttribute("RESULT", e.getMessage());
            throw new ExternalException(e);
        }
        finally {
            OIOSAML3Service.getAuditService().auditLog(auditBuilder);
        }

        OIOSAML3Service.getAuditService().auditLog(AuditRequestUtil
                .createBasicAuditBuilder(httpServletRequest, "BSA7", "CreateSession")
                .withAuthnAttribute("SP_SESSION_ID", session.getId())
                .withAuthnAttribute("SP_SESSION_TIMEOUT", String.valueOf(session.getMaxInactiveInterval())));

        // Set NSISLevel to what was provided by the Assertion
        Map<String, String> attributeMap = SamlHelper.extractAttributeValues(assertion.getAttributeStatements().get(0));
        String loa = attributeMap.get(Constants.LOA);
        String assuranceLevel = attributeMap.get(Constants.ASSURANCE_LEVEL);
        NSISLevel nsisLevel = NSISLevel.getNSISLevelFromAttributeValue(loa, NSISLevel.NONE);

        session.setAttribute(Constants.SESSION_NSIS_LEVEL, nsisLevel);
        if(assuranceLevel != null) {
            session.setAttribute(Constants.SESSION_ASSURANCE_LEVEL, assuranceLevel);
        }

        session.setAttribute(Constants.SESSION_AUTHENTICATED, "true");
        session.setAttribute(Constants.SESSION_SESSION_INDEX, getSessionIndex(assertion));


        session.setAttribute(Constants.SESSION_ASSERTION, new AssertionWrapper(assertion));

        NameID nameID = assertion.getSubject().getNameID();
        session.setAttribute(Constants.SESSION_NAME_ID, nameID.getValue());
        session.setAttribute(Constants.SESSION_NAME_ID_FORMAT, nameID.getFormat());

        // Redirect
        String attribute = Objects.toString(session.getAttribute(Constants.SESSION_REQUESTED_PATH),"");

        // redirect to SESSION_REQUESTED_PATH or to login page if not found
        String url = StringUtil.defaultIfEmpty(attribute,
                StringUtil.getUrl(httpServletRequest, OIOSAML3Service.getConfig().getLoginPage()));

        OIOSAML3Service.getAuditService().auditLog(AuditRequestUtil
                .createBasicAuditBuilder(httpServletRequest, "BSA8", "SendRedirect")
                .withAuthnAttribute("URL_REDIRECT",url));

        httpServletResponse.sendRedirect(url);
    }

    private String getSessionIndex(Assertion assertion) {
        if (assertion.getAuthnStatements() != null && assertion.getAuthnStatements().size() > 0) {
            for (AuthnStatement authnStatement : assertion.getAuthnStatements()) {
                if (StringUtil.isNotEmpty(authnStatement.getSessionIndex())) {
                    return authnStatement.getSessionIndex();
                }
            }
        }

        return null;
    }
}
