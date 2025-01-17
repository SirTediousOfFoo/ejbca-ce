/*************************************************************************
 *                                                                       *
 *  EJBCA Community: The OpenSource Certificate Authority                *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/

package org.ejbca.util;

import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.cesecore.authentication.tokens.AuthenticationToken;
import org.cesecore.authorization.AuthorizationSessionLocal;
import org.cesecore.authorization.control.StandardRules;
import org.cesecore.configuration.GlobalConfigurationSessionLocal;
import org.ejbca.config.AvailableProtocolsConfiguration;
import org.ejbca.core.ejb.rest.EjbcaRestHelperSessionLocal;
import org.ejbca.core.model.util.EjbLocalHelper;

/**
 * <p>This filter is responsible for disabling access to parts of EJBCA based
 * on the URL of the request and the current system configuration, which
 * is known as "Modular Protocol Configuration".
 * The purpose of Modular Protocol Configuration is to reduce the attack surface by
 * allowing an administrator to completely disable access to node-local services which
 * should not be used.</p>
 * <p>The servlet filter implemented by this class only filters incoming requests.
 * The URL of the request is matched against a service in EJBCA, such as the
 * CMP protocol or the Public web. If this service is disabled according to the policy
 * stored in system configuration, the request is filtered and an HTTP response with
 * error code 403 is sent back to the client. Otherwise, the request is let through
 * unaltered.
 */
public class ServiceControlFilter implements Filter {
    private static final Logger log = Logger.getLogger(ServiceControlFilter.class);
    
    private AvailableProtocolsConfiguration availableProtocolsConfiguration;
    
    private String serviceName;

    private GlobalConfigurationSessionLocal globalConfigurationSession;
    
    private AuthorizationSessionLocal authorizationSession;
    
    private EjbcaRestHelperSessionLocal ejbcaRestHelperSession;
    

    @Override
    public void destroy() {
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        serviceName = filterConfig.getInitParameter("serviceName");
        // Since this filter is referenced in ejbca-common-web module and that module is referenced by 
        // cmpHttpProxy module, to make cmpHttpProxy module deploy-able in JEE servers we initialize 
        // the globalConfigurationSession bean here instead of using the EJB annotation.
        globalConfigurationSession = new EjbLocalHelper().getGlobalConfigurationSession();
        authorizationSession = new EjbLocalHelper().getAuthorizationSession();
        ejbcaRestHelperSession = new EjbLocalHelper().getEjbcaRestHelperSession();
        if (log.isDebugEnabled()) {
            log.debug("Initialized service control filter for '" + serviceName + "'.");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest httpRequest = (HttpServletRequest) request;
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
        availableProtocolsConfiguration = (AvailableProtocolsConfiguration) globalConfigurationSession
                .getCachedConfiguration(AvailableProtocolsConfiguration.CONFIGURATION_ID);
        
        // Note: Swagger gets serviceName == AvailableProtocols.REST_CERTIFICATE_MANAGEMENT
        if (!availableProtocolsConfiguration.getProtocolStatus(serviceName)) {
            if (log.isDebugEnabled()) {
                log.debug("Access to service " + serviceName + " is disabled. HTTP request " + httpRequest.getRequestURL() + " is filtered.");
            }
            
            if(serviceName.equalsIgnoreCase(
                    AvailableProtocolsConfiguration.AvailableProtocols.REST_CONFIGDUMP.getName())) {
                AuthenticationToken authenticationToken = getAdmin(httpRequest);
                if(authenticationToken!=null &&
                        authorizationSession.isAuthorized(authenticationToken, StandardRules.ROLE_ROOT.resource())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Access to disabled service " + serviceName + " is allowed due to superadmin access. "
                                                + "HTTP request " + httpRequest.getRequestURL() + " is let through.");
                    }
                    chain.doFilter(request, response);
                    return;
                }
            }
            httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN, "This service has been disabled.");
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Access to service " + serviceName + " is allowed. HTTP request " + httpRequest.getRequestURL() + " is let through.");
        }
        chain.doFilter(request, response);
    }
    
    private AuthenticationToken getAdmin(HttpServletRequest requestContext) {
        if (requestContext == null) {
            return null;
        }

        final X509Certificate[] certificates = (X509Certificate[]) requestContext.getAttribute("javax.servlet.request.X509Certificate");
        final X509Certificate certificate = certificates != null ? certificates[0] : null;
        final String oauthBearerToken = HttpTools.extractBearerAuthorization(requestContext.getHeader(HttpTools.AUTHORIZATION_HEADER));

        if (certificate == null && StringUtils.isEmpty(oauthBearerToken)) {
            return null;
        }

        try {
            return ejbcaRestHelperSession.getAdmin(false, certificate, oauthBearerToken);
        } catch (Exception e) {
            return null;
        }
    }
}
