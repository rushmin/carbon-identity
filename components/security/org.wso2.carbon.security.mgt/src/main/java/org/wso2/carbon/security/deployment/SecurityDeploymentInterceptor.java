/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.security.deployment;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.description.AxisModule;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.AxisServiceGroup;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.engine.AxisEvent;
import org.apache.axis2.engine.AxisObserver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.neethi.Policy;
import org.apache.neethi.PolicyComponent;
import org.apache.neethi.PolicyReference;
import org.apache.neethi.builders.xml.XmlPrimtiveAssertion;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.context.RegistryType;
import org.wso2.carbon.core.RegistryResources;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.ResourceImpl;
import org.wso2.carbon.registry.core.jdbc.utils.Transaction;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.security.SecurityConfigParams;
import org.wso2.carbon.security.SecurityConstants;
import org.wso2.carbon.security.SecurityScenario;
import org.wso2.carbon.security.SecurityScenarioDatabase;
import org.wso2.carbon.security.SecurityServiceHolder;
import org.wso2.carbon.security.util.RahasUtil;
import org.wso2.carbon.security.util.SecurityConfigParamBuilder;
import org.wso2.carbon.security.util.ServerCrypto;
import org.wso2.carbon.security.util.ServicePasswordCallbackHandler;
import org.wso2.carbon.security.util.XmlConfiguration;
import org.wso2.carbon.user.api.AuthorizationManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.Axis2ConfigurationContextObserver;
import org.wso2.carbon.utils.PreAxisConfigurationPopulationObserver;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.net.URL;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Properties;

/**
 * This is a deployment interceptor which handles service specific security configurations on
 * service deployment events. It is also published as an OSGi service, so that Carbon core can
 * add this to the AxisConfiguration.
 * <p/>
 * NOTE: This is a special type of AxisObserver, which can be used only within an OSGi framework
 * hence should not be added to the axis2.xml directly. If done so, it will throw NPEs, since
 * the registry & userRealm references are set through the OSGi decalative service framework.
 *
 * @scr.component name="org.wso2.carbon.security.deployment.SecurityDeploymentInterceptor"
 * immediate="true"
 * @scr.reference name="registry.service"
 * interface="org.wso2.carbon.registry.core.service.RegistryService"
 * cardinality="1..1" policy="dynamic" bind="setRegistryService"
 * unbind="unsetRegistryService"
 * @scr.reference name="user.realmservice.default" interface="org.wso2.carbon.user.core.service.RealmService"
 * cardinality="1..1" policy="dynamic" bind="setRealmService" unbind="unsetRealmService"
 */

public class SecurityDeploymentInterceptor implements AxisObserver {
    private static final Log log = LogFactory.getLog(SecurityDeploymentInterceptor.class);


    protected void activate(ComponentContext ctxt) {
        BundleContext bundleCtx = ctxt.getBundleContext();
        try {
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            carbonContext.setTenantId(MultitenantConstants.SUPER_TENANT_ID);

            loadSecurityScenarios(SecurityServiceHolder.getRegistryService().getConfigSystemRegistry(),
                    bundleCtx);
        } catch (Exception e) {
            String msg = "Cannot load security scenarios";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }

        try {
            addKeystores();
        } catch (Exception e) {
            String msg = "Cannot add keystores";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
        // Publish the OSGi service
        Dictionary props = new Hashtable();
        props.put(CarbonConstants.AXIS2_CONFIG_SERVICE, AxisObserver.class.getName());
        bundleCtx.registerService(AxisObserver.class.getName(), this, props);

        PreAxisConfigurationPopulationObserver preAxisConfigObserver =
                new PreAxisConfigurationPopulationObserver() {
                    public void createdAxisConfiguration(AxisConfiguration axisConfiguration) {
                        init(axisConfiguration);
                        axisConfiguration.addObservers(SecurityDeploymentInterceptor.this);
                    }
                };
        bundleCtx.registerService(PreAxisConfigurationPopulationObserver.class.getName(),
                preAxisConfigObserver, null);

        // Publish an OSGi service to listen tenant configuration context creation events
        Dictionary properties = new Hashtable();
        properties.put(CarbonConstants.AXIS2_CONFIG_SERVICE,
                Axis2ConfigurationContextObserver.class.getName());
        bundleCtx.registerService(Axis2ConfigurationContextObserver.class.getName(),
                new SecurityDeploymentListener(), properties);
    }

    public void init(AxisConfiguration axisConfig) {

    }

    public void moduleUpdate(AxisEvent event, AxisModule module) {
        // This method will not be used
    }

    public void serviceGroupUpdate(AxisEvent event, AxisServiceGroup serviceGroup) {
        // This method will not be used
    }

    public void serviceUpdate(AxisEvent axisEvent, AxisService axisService) {

        if (axisEvent.getEventType() == AxisEvent.SERVICE_DEPLOY) {
            try {

                if (axisService.getPolicySubject() != null &&
                    axisService.getPolicySubject().getAttachedPolicyComponents() != null) {

                    if (log.isDebugEnabled()) {
                        log.debug("Policies found on axis service");
                    }

                    Iterator iterator = axisService.getPolicySubject().
                            getAttachedPolicyComponents().iterator();

                    String policyId = null;
                    while (iterator.hasNext()) {
                        PolicyComponent currentPolicyComponent = (PolicyComponent) iterator.next();
                        if (currentPolicyComponent instanceof Policy) {
                            policyId = ((Policy) currentPolicyComponent).getId();
                        } else if (currentPolicyComponent instanceof PolicyReference) {   //TODO: check this scenario
                            policyId = ((PolicyReference) currentPolicyComponent).getURI().substring(1);
                        }

                        if (policyId != null && isSecPolicy(policyId)) {

                            if (log.isDebugEnabled()) {
                                log.debug("Policy " + policyId + " is identified as a security " +
                                          "policy and trying to apply security parameters");
                            }

                            SecurityScenario scenario = SecurityScenarioDatabase.getByWsuId(policyId);
                            if (scenario == null) {
                                // if there is no security scenario id,  put default id
                                if (log.isDebugEnabled()) {
                                    log.debug("Policy " + policyId + " does not belongs to a" +
                                              " pre-defined security scenario. " +
                                              "So treating as a custom policy");
                                }
                                SecurityScenario securityScenario = new SecurityScenario();
                                securityScenario.setScenarioId(
                                        SecurityConstants.CUSTOM_SECURITY_SCENARIO);
                                securityScenario.setWsuId(policyId);
                                securityScenario.setGeneralPolicy(false);
                                securityScenario.setSummary(
                                        SecurityConstants.CUSTOM_SECURITY_SCENARIO_SUMMARY);
                                SecurityScenarioDatabase.put(policyId, securityScenario);
                                scenario = securityScenario;
                            }
                            applySecurityParameters(axisService, scenario,
                                                    (Policy) currentPolicyComponent);
                        }
                    }
                } else {
                    return;
                }

            } catch (Exception e) {
                String msg = "Cannot handle service DEPLOY event for service: " +
                             axisService.getName();
                log.error(msg, e);
                throw new RuntimeException(msg, e);
            }
        }

    }

    private void loadSecurityScenarios(Registry registry,
                                       BundleContext bundleContext) throws Exception {

        // TODO: Load into all tenant DBs
        // Load security scenarios
        URL resource = bundleContext.getBundle().getResource("/scenarios/scenario-config.xml");
        XmlConfiguration xmlConfiguration = new XmlConfiguration(resource.openStream(),
                SecurityConstants.SECURITY_NAMESPACE);

        OMElement[] elements = xmlConfiguration.getElements("//ns:Scenario");
        try {
            boolean transactionStarted = Transaction.isStarted();
            if (!transactionStarted) {
                registry.beginTransaction();
            }

            for (OMElement scenarioEle : elements) {
                SecurityScenario scenario = new SecurityScenario();
                String scenarioId = scenarioEle.getAttribute(SecurityConstants.ID_QN)
                        .getAttributeValue();

                scenario.setScenarioId(scenarioId);
                scenario.setSummary(scenarioEle.getFirstChildWithName(SecurityConstants.SUMMARY_QN)
                        .getText());
                scenario.setDescription(scenarioEle.getFirstChildWithName(
                        SecurityConstants.DESCRIPTION_QN).getText());
                scenario.setCategory(scenarioEle.getFirstChildWithName(SecurityConstants.CATEGORY_QN)
                        .getText());
                scenario.setWsuId(scenarioEle.getFirstChildWithName(SecurityConstants.WSUID_QN)
                        .getText());
                scenario.setType(scenarioEle.getFirstChildWithName(SecurityConstants.TYPE_QN).getText());

                String resourceUri = SecurityConstants.SECURITY_POLICY + "/" + scenarioId;

                for (Iterator modules = scenarioEle.getFirstChildWithName(SecurityConstants.MODULES_QN)
                        .getChildElements(); modules.hasNext(); ) {
                    String module = ((OMElement) modules.next()).getText();
                    scenario.addModule(module);
                }

                // Save it in the DB
                SecurityScenarioDatabase.put(scenarioId, scenario);

                // Store the scenario in the Registry
                if (!scenarioId.equals(SecurityConstants.SCENARIO_DISABLE_SECURITY) &&
                        !scenarioId.equals(SecurityConstants.POLICY_FROM_REG_SCENARIO)) {
                    Resource scenarioResource = new ResourceImpl();
                    scenarioResource.
                            setContentStream(bundleContext.getBundle().
                                    getResource("scenarios/" + scenarioId + "-policy.xml").openStream());
                    scenarioResource.setMediaType("application/policy+xml");
                    if (!registry.resourceExists(resourceUri)) {
                        registry.put(resourceUri, scenarioResource);
                    }

                    // Cache the resource in-memory in order to add it to the newly created tenants
                    SecurityServiceHolder.addPolicyResource(resourceUri, scenarioResource);
                }
            }
            if (!transactionStarted) {
                registry.commitTransaction();
            }
        } catch (Exception e) {
            registry.rollbackTransaction();
            throw e;
        }
    }

    private void addKeystores() throws Exception {
        Registry registry = SecurityServiceHolder.getRegistryService().getGovernanceSystemRegistry();
        try {
            boolean transactionStarted = Transaction.isStarted();
            if (!transactionStarted) {
                registry.beginTransaction();
            }
            if (!registry.resourceExists(SecurityConstants.KEY_STORES)) {
                Collection kstores = registry.newCollection();
                registry.put(SecurityConstants.KEY_STORES, kstores);

                Resource primResource = registry.newResource();
                if (!registry.resourceExists(RegistryResources.SecurityManagement.PRIMARY_KEYSTORE_PHANTOM_RESOURCE)) {
                    registry.put(RegistryResources.SecurityManagement.PRIMARY_KEYSTORE_PHANTOM_RESOURCE,
                            primResource);
                }
            }
            if (!transactionStarted) {
                registry.commitTransaction();
            }
        } catch (Exception e) {
            registry.rollbackTransaction();
            throw e;
        }
    }

    private void applySecurityParameters(AxisService service, SecurityScenario secScenario,
                                         Policy policy) {
        try {

            UserRealm userRealm = (UserRealm) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                    .getUserRealm();

            UserRegistry govRegistry = (UserRegistry) PrivilegedCarbonContext
                    .getThreadLocalCarbonContext().getRegistry(RegistryType.SYSTEM_GOVERNANCE);

            String serviceGroupId = service.getAxisServiceGroup().getServiceGroupName();
            String serviceName = service.getName();

            SecurityConfigParams configParams =
                    SecurityConfigParamBuilder.getSecurityParams(getSecurityConfig(policy));

            // Set Trust (Rahas) Parameters
            if (secScenario.getModules().contains(SecurityConstants.TRUST_MODULE)) {
                AxisModule trustModule = service.getAxisConfiguration()
                        .getModule(SecurityConstants.TRUST_MODULE);
                if (log.isDebugEnabled()) {
                    log.debug("Enabling trust module : " + SecurityConstants.TRUST_MODULE);
                }

                service.disengageModule(trustModule);
                service.engageModule(trustModule);

                Properties cryptoProps = new Properties();
                cryptoProps.setProperty(ServerCrypto.PROP_ID_PRIVATE_STORE,
                                        configParams.getPrivateStore());
                cryptoProps.setProperty(ServerCrypto.PROP_ID_DEFAULT_ALIAS,
                                        configParams.getKeyAlias());
                if (configParams.getTrustStores() != null) {
                    cryptoProps.setProperty(ServerCrypto.PROP_ID_TRUST_STORES,
                                            configParams.getTrustStores());
                }
                service.addParameter(RahasUtil.getSCTIssuerConfigParameter(
                        ServerCrypto.class.getName(), cryptoProps, -1, null, true, true));

                service.addParameter(RahasUtil.getTokenCancelerConfigParameter());

            }

            // Authorization
            String allowRolesParameter = configParams.getAllowedRoles();
            if (allowRolesParameter != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Authorizing roles " + allowRolesParameter);
                }

                AuthorizationManager manager = userRealm.getAuthorizationManager();
                String resourceName = serviceGroupId + "/" + serviceName;
                String[] roles = manager.
                        getAllowedRolesForResource(resourceName,
                                                   UserCoreConstants.INVOKE_SERVICE_PERMISSION);
                if (roles != null) {
                    for (String role : roles) {
                        manager.clearRoleAuthorization(role, resourceName,
                                                       UserCoreConstants.INVOKE_SERVICE_PERMISSION);
                    }
                }

                String[] allowRoles = allowRolesParameter.split(",");
                if (allowRoles != null) {
                    for (String role : allowRoles) {
                        manager.authorizeRole(role, resourceName,
                                              UserCoreConstants.INVOKE_SERVICE_PERMISSION);
                    }
                }
            }

            // Password Callback Handler
            ServicePasswordCallbackHandler handler =
                    new ServicePasswordCallbackHandler(configParams, serviceGroupId, serviceName,
                                                       govRegistry, userRealm);

            Parameter param = new Parameter();
            param.setName(WSHandlerConstants.PW_CALLBACK_REF);
            param.setValue(handler);
            service.addParameter(param);

        } catch (Throwable e) {
        //TODO: Copied from 4.2.2.
        //TODO: Not sure why we are catching throwable. Need to check error handling is correct
            String msg = "Cannot apply security parameters";
            log.error(msg, e);
        }
    }

    /**
     * Extract carbon security config element from the Policy
     *
     * @param policy Security Policy
     * @return security config element
     */
    private OMElement getSecurityConfig(Policy policy) {
        Iterator<PolicyComponent> iterator = policy.getPolicyComponents().iterator();
        while (iterator.hasNext()) {
            PolicyComponent component = iterator.next();
            if (component instanceof XmlPrimtiveAssertion) {
                OMElement value = ((XmlPrimtiveAssertion) component).getValue();
                if (value != null &&
                    SecurityConfigParamBuilder.SecurityConfigQName.equals(value.getQName())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Carbon Security config found : " + value.toString());
                    }
                    return value;
                }
            }
        }
        return null;
    }

    public void addParameter(Parameter param) throws AxisFault {
        // This method will not be used
    }

    public void deserializeParameters(OMElement parameterElement) throws AxisFault {
        // This method will not be used
    }

    public Parameter getParameter(String name) {
        // This method will not be used
        return null;
    }

    public ArrayList getParameters() {
        // This method will not be used
        return new ArrayList();
    }

    public boolean isParameterLocked(String parameterName) {
        // This method will not be used
        return false;
    }

    public void removeParameter(Parameter param) throws AxisFault {
        // This method will not be used
    }

    protected void setRegistryService(RegistryService registryService) {
        SecurityServiceHolder.setRegistryService(registryService);
    }

    protected void setRealmService(RealmService realmService) {
        SecurityServiceHolder.setRealmService(realmService);
    }

    protected void unsetRealmService(RealmService realmService) {
        SecurityServiceHolder.setRealmService(null);
    }

    protected void unsetRegistryService(RegistryService registryService) {
        SecurityServiceHolder.setRegistryService(null);
    }

    /**
     * Check whether policyID belongs to a security scenario
     *
     * @param policyId policy id
     * @return whether policyID belongs to a security scenario
     */
    private boolean isSecPolicy(String policyId) {
        if ("RMPolicy".equals(policyId) || "WSO2CachingPolicy".equals(policyId)
            || "WSO2ServiceThrottlingPolicy".equals(policyId)) {
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("Policy ID : " + policyId + " is identified as a security policy");
        }

        return true;
    }
}
