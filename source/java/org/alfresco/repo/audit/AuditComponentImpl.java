/*
 * Copyright (C) 2005-2010 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.repo.audit;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.audit.extractor.DataExtractor;
import org.alfresco.repo.audit.generator.DataGenerator;
import org.alfresco.repo.audit.model.AuditApplication;
import org.alfresco.repo.audit.model.AuditModelRegistry;
import org.alfresco.repo.domain.audit.AuditDAO;
import org.alfresco.repo.domain.propval.PropertyValueDAO;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport.TxnReadState;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.audit.AuditQueryParameters;
import org.alfresco.service.cmr.audit.AuditService.AuditQueryCallback;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.PathMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.surf.util.ParameterCheck;

/**
 * The default audit component implementation. TODO: Implement before, after and exception filtering. At the moment
 * these filters are ignored. TODO: Respect audit internal - at the moment audit internal is fixed to false.
 * <p/>
 * The V3.2 audit functionality is contained within the same component.  When the newer audit
 * implementation has been tested and approved, then older ones will be deprecated as necessary.
 * 
 * @author Andy Hind
 * @author Derek Hulley
 */
public class AuditComponentImpl implements AuditComponent
{
    private static Log logger = LogFactory.getLog(AuditComponentImpl.class);

    private AuditModelRegistry auditModelRegistry;
    private PropertyValueDAO propertyValueDAO;
    private AuditDAO auditDAO;
    private TransactionService transactionService;
    
    /**
     * Default constructor
     */
    public AuditComponentImpl()
    {
    }
    
    /**
     * Set the registry holding the audit models
     * @since 3.2
     */
    public void setAuditModelRegistry(AuditModelRegistry auditModelRegistry)
    {
        this.auditModelRegistry = auditModelRegistry;
    }

    /**
     * Set the DAO for manipulating property values
     * @since 3.2
     */
    public void setPropertyValueDAO(PropertyValueDAO propertyValueDAO)
    {
        this.propertyValueDAO = propertyValueDAO;
    }
    
    /**
     * Set the DAO for accessing audit data
     * @since 3.2
     */
    public void setAuditDAO(AuditDAO auditDAO)
    {
        this.auditDAO = auditDAO;
    }

    /**
     * Set the service used to start new transactions
     */
    public void setTransactionService(TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public void deleteAuditEntries(String applicationName, Long fromTime, Long toTime)
    {
        ParameterCheck.mandatory("applicationName", applicationName);
        AlfrescoTransactionSupport.checkTransactionReadState(true);
        
        AuditApplication application = auditModelRegistry.getAuditApplicationByName(applicationName);
        if (application == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No audit application named '" + applicationName + "' has been registered.");
            }
            return;
        }
        
        Long applicationId = application.getApplicationId();
        
        auditDAO.deleteAuditEntries(applicationId, fromTime, toTime);
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Delete audit entries for " + applicationName + " (" + fromTime + " to " + toTime);
        }
    }

    /**
     * @param application       the audit application object
     * @return                  Returns a copy of the set of disabled paths associated with the application
     */
    @SuppressWarnings("unchecked")
    private Set<String> getDisabledPaths(AuditApplication application)
    {
        try
        {
            Long disabledPathsId = application.getDisabledPathsId();
            Set<String> disabledPaths = (Set<String>) propertyValueDAO.getPropertyById(disabledPathsId);
            return new HashSet<String>(disabledPaths);
        }
        catch (Throwable e)
        {
            // Might be an invalid ID, somehow
            auditModelRegistry.loadAuditModels();
            throw new AlfrescoRuntimeException("Unabled to get AuditApplication disabled paths: " + application, e);
        }
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public boolean isAuditEnabled()
    {
        return auditModelRegistry.isAuditEnabled();                
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public boolean isSourcePathMapped(String sourcePath)
    {
        return isAuditEnabled() && !auditModelRegistry.getAuditPathMapper().isEmpty();                
    }
    
    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public boolean isAuditPathEnabled(String applicationName, String path)
    {
        ParameterCheck.mandatory("applicationName", applicationName);
        ParameterCheck.mandatory("path", path);
        AlfrescoTransactionSupport.checkTransactionReadState(false);
        
        AuditApplication application = auditModelRegistry.getAuditApplicationByName(applicationName);
        if (application == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No audit application named '" + applicationName + "' has been registered.");
            }
            return false;
        }
        // Check the path against the application
        application.checkPath(path);

        Set<String> disabledPaths = getDisabledPaths(application);
        
        // Check if there are any entries that match or superced the given path
        String disablingPath = null;;
        for (String disabledPath : disabledPaths)
        {
            if (path.startsWith(disabledPath))
            {
                disablingPath = disabledPath;
                break;
            }
        }
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Audit path enabled check: \n" +
                    "   Application:    " + applicationName + "\n" +
                    "   Path:           " + path + "\n" +
                    "   Disabling Path: " + disablingPath);
        }
        return disablingPath == null;
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public void enableAudit(String applicationName, String path)
    {
        ParameterCheck.mandatory("applicationName", applicationName);
        ParameterCheck.mandatory("path", path);
        AlfrescoTransactionSupport.checkTransactionReadState(true);
        
        AuditApplication application = auditModelRegistry.getAuditApplicationByName(applicationName);
        if (application == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No audit application named '" + applicationName + "' has been registered.");
            }
            return;
        }
        // Check the path against the application
        application.checkPath(path);

        Long disabledPathsId = application.getDisabledPathsId();
        Set<String> disabledPaths = getDisabledPaths(application);
        
        // Remove any paths that start with the given path
        boolean changed = false;
        Iterator<String> iterateDisabledPaths = disabledPaths.iterator();
        while (iterateDisabledPaths.hasNext())
        {
            String disabledPath = iterateDisabledPaths.next();
            if (disabledPath.startsWith(path))
            {
                iterateDisabledPaths.remove();
                changed = true;
            }
        }
        // Persist, if necessary
        if (changed)
        {
            propertyValueDAO.updateProperty(disabledPathsId, (Serializable) disabledPaths);
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Audit disabled paths updated: \n" +
                        "   Application: " + applicationName + "\n" +
                        "   Disabled:    " + disabledPaths);
            }
        }
        // Done
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public void disableAudit(String applicationName, String path)
    {
        ParameterCheck.mandatory("applicationName", applicationName);
        ParameterCheck.mandatory("path", path);
        AlfrescoTransactionSupport.checkTransactionReadState(true);
        
        AuditApplication application = auditModelRegistry.getAuditApplicationByName(applicationName);
        if (application == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No audit application named '" + applicationName + "' has been registered.");
            }
            return;
        }
        // Check the path against the application
        application.checkPath(path);
        
        Long disabledPathsId = application.getDisabledPathsId();
        Set<String> disabledPaths = getDisabledPaths(application);
        
        // Shortcut if the disabled paths contain the exact path
        if (disabledPaths.contains(path))
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Audit disable path already present: \n" +
                        "   Path:       " + path);
            }
            return;
        }
        
        // Bring the set up to date by stripping out unwanted paths
        Iterator<String> iterateDisabledPaths = disabledPaths.iterator();
        while (iterateDisabledPaths.hasNext())
        {
            String disabledPath = iterateDisabledPaths.next();
            if (disabledPath.startsWith(path))
            {
                // We will be superceding this
                iterateDisabledPaths.remove();
            }
            else if (path.startsWith(disabledPath))
            {
                // There is already a superceding path
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Audit disable path superceded: \n" +
                            "   Path:          " + path + "\n" +
                            "   Superceded by: " + disabledPath);
                }
                return;
            }
        }
        // Add our path in
        disabledPaths.add(path);
        // Upload the new set
        propertyValueDAO.updateProperty(disabledPathsId, (Serializable) disabledPaths);
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "Audit disabled paths updated: \n" +
                    "   Application: " + applicationName + "\n" +
                    "   Disabled:    " + disabledPaths);
        }
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public void resetDisabledPaths(String applicationName)
    {
        ParameterCheck.mandatory("applicationName", applicationName);
        AlfrescoTransactionSupport.checkTransactionReadState(true);
        
        AuditApplication application = auditModelRegistry.getAuditApplicationByName(applicationName);
        if (application == null)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("No audit application named '" + applicationName + "' has been registered.");
            }
            return;
        }
        Long disabledPathsId = application.getDisabledPathsId();
        propertyValueDAO.updateProperty(disabledPathsId, (Serializable) Collections.emptySet());
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Removed all disabled paths for application " + applicationName);
        }
    }

    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public Map<String, Serializable> recordAuditValues(String rootPath, Map<String, Serializable> values)
    {
        ParameterCheck.mandatory("rootPath", rootPath);
        AuditApplication.checkPathFormat(rootPath);

        if (values == null || values.isEmpty() || !isSourcePathMapped(rootPath))
        {
            return Collections.emptyMap();
        }
        
        // Build the key paths using the session root path
        Map<String, Serializable> pathedValues = new HashMap<String, Serializable>(values.size() * 2);
        for (Map.Entry<String, Serializable> entry : values.entrySet())
        {
            String pathElement = entry.getKey();
            String path = AuditApplication.buildPath(rootPath, pathElement);
            pathedValues.put(path, entry.getValue());
        }
        
        // Translate the values map
        PathMapper pathMapper = auditModelRegistry.getAuditPathMapper();
        final Map<String, Serializable> mappedValues = pathMapper.convertMap(pathedValues);
        if (mappedValues.isEmpty())
        {
            return mappedValues;
        }
        
        // We have something to record.  Start a transaction, if necessary
        TxnReadState txnState = AlfrescoTransactionSupport.getTransactionReadState();
        switch (txnState)
        {
        case TXN_NONE:
        case TXN_READ_ONLY:
            // New transaction
            RetryingTransactionCallback<Map<String, Serializable>> callback =
                    new RetryingTransactionCallback<Map<String,Serializable>>()
            {
                public Map<String, Serializable> execute() throws Throwable
                {
                    return recordAuditValuesImpl(mappedValues);
                }
            };
            return transactionService.getRetryingTransactionHelper().doInTransaction(callback, false, true);
        case TXN_READ_WRITE:
            return recordAuditValuesImpl(mappedValues);
        default:
            throw new IllegalStateException("Unknown txn state: " + txnState);
        }
    }
    
    /**
     * {@inheritDoc}
     * @since 3.2
     */
    public Map<String, Serializable> recordAuditValuesImpl(Map<String, Serializable> mappedValues)
    {
        // Group the values by root path
        Map<String, Map<String, Serializable>> mappedValuesByRootKey = new HashMap<String, Map<String,Serializable>>();
        for (Map.Entry<String, Serializable> entry : mappedValues.entrySet())
        {
            String path = entry.getKey();
            String rootKey = AuditApplication.getRootKey(path);
            Map<String, Serializable> rootKeyMappedValues = mappedValuesByRootKey.get(rootKey);
            if (rootKeyMappedValues == null)
            {
                rootKeyMappedValues = new HashMap<String, Serializable>(7);
                mappedValuesByRootKey.put(rootKey, rootKeyMappedValues);
            }
            rootKeyMappedValues.put(path, entry.getValue());
        }

        Map<String, Serializable> allAuditedValues = new HashMap<String, Serializable>(mappedValues.size()*2+1);
        // Now audit for each of the root keys
        for (Map.Entry<String, Map<String, Serializable>> entry : mappedValuesByRootKey.entrySet())
        {
            String rootKey = entry.getKey();
            Map<String, Serializable> rootKeyMappedValues = entry.getValue();
            // Get the application
            AuditApplication application = auditModelRegistry.getAuditApplicationByKey(rootKey);
            if (application == null)
            {
                // There is no application that uses the root key
                logger.debug(
                        "There is no application for root key: " + rootKey);
                continue;
            }
            // Get the disabled paths
            Set<String> disabledPaths = getDisabledPaths(application);
            // Do a quick elimination if the root path is disabled
            if (disabledPaths.contains(AuditApplication.buildPath(rootKey)))
            {
                // The root key has been disabled for this application
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                            "Audit values root path has been excluded by disabled paths: \n" +
                            "   Application: " + application + "\n" +
                            "   Root Path:   " + AuditApplication.buildPath(rootKey));
                }
                continue;
            }
            // Do the audit
            Map<String, Serializable> rootKeyAuditValues = audit(application, disabledPaths, rootKeyMappedValues);
            allAuditedValues.putAll(rootKeyAuditValues);
        }
        // Done
        return allAuditedValues;
    }

    /**
     * Audit values for a given application.  No path checking is done.
     * 
     * @param application           the audit application to audit to
     * @param disabledPaths         the application's disabled paths
     * @param values                the values to store keyed by <b>full paths</b>.
     * @return                      Returns all values as audited
     */
    private Map<String, Serializable> audit(
            final AuditApplication application,
            Set<String> disabledPaths,
            final Map<String, Serializable> values)
    {
        // Get the model ID for the application
        Long applicationId = application.getApplicationId();
        if (applicationId == null)
        {
            throw new AuditException("No persisted instance exists for audit application: " + application);
        }

        // Eliminate any paths that have been disabled
        Iterator<String> pathedValuesKeyIterator = values.keySet().iterator();
        while(pathedValuesKeyIterator.hasNext())
        {
            String pathedValueKey = pathedValuesKeyIterator.next();
            for (String disabledPath : disabledPaths)
            {
                if (pathedValueKey.startsWith(disabledPath))
                {
                    // The pathed value is excluded
                    pathedValuesKeyIterator.remove();
                }
            }
        }
        // Check if there is anything left
        if (values.size() == 0)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        "Audit values have all been excluded by disabled paths: \n" +
                        "   Application: " + application + "\n" +
                        "   Values:      " + values);
            }
            return Collections.emptyMap();
        }
        
        // Generate data
        Map<String, DataGenerator> generators = application.getDataGenerators(values.keySet());
        Map<String, Serializable> auditData = generateData(generators);
        
        // Now extract values
        Map<String, Serializable> extractedData = AuthenticationUtil.runAs(new RunAsWork<Map<String, Serializable>>()
        {
            public Map<String, Serializable> doWork() throws Exception
            {
                return extractData(application, values);
            }
        }, AuthenticationUtil.getSystemUserName());
        
        // Combine extracted and generated values (extracted data takes precedence)
        auditData.putAll(extractedData);

        // Time and username are intrinsic
        long time = System.currentTimeMillis();
        String username = AuthenticationUtil.getFullyAuthenticatedUser();
        
        Long entryId = null;
        if (!auditData.isEmpty())
        {
            // Persist the values
            entryId = auditDAO.createAuditEntry(applicationId, time, username, auditData);
        }
        
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug(
                    "New audit entry: \n" +
                    "   Application ID: " + applicationId + "\n" +
                    "   Entry ID:       " + entryId + "\n" +
                    "   Values:         " + values + "\n" +
                    "   Audit Data:     " + auditData);
        }
        return auditData;
    }
    
    /**
     * Extracts data from a given map using data extractors from the given application.
     * 
     * @param application           the application providing the data extractors
     * @param values                the data values from which to generate data
     * @return                      Returns a map of derived data keyed by full path
     * 
     * @since 3.2
     */
    private Map<String, Serializable> extractData(
            AuditApplication application,
            Map<String, Serializable> values)
    {
        Map<String, Serializable> newData = new HashMap<String, Serializable>(values.size() + 5);
        for (Map.Entry<String, Serializable> entry : values.entrySet())
        {
            String path = entry.getKey();
            Serializable value = entry.getValue();
            // Get the applicable extractor
            Map<String, DataExtractor> extractors = application.getDataExtractors(path);
            for (Map.Entry<String, DataExtractor> extractorElement : extractors.entrySet())
            {
                String extractorPath = extractorElement.getKey();
                DataExtractor extractor = extractorElement.getValue();
                // Check if the extraction is supported
                if (!extractor.isSupported(value))
                {
                    continue;
                }
                // Use the extractor to pull the value out
                final Serializable data;
                try
                {
                    data = extractor.extractData(value);
                }
                catch (Throwable e)
                {
                    throw new AlfrescoRuntimeException(
                            "Failed to extract audit data: \n" +
                            "   Path:      " + path + "\n" +
                            "   Raw value: " + value + "\n" +
                            "   Extractor: " + extractor,
                            e);
                }
                // Add it to the map
                newData.put(extractorPath, data);
            }
        }
        // Done
        if (logger.isDebugEnabled())
        {
            logger.debug("Extracted audit data: \n" +
                    "   Application: " + application + "\n" +
                    "   Raw values:  " + values + "\n" +
                    "   Extracted: " + newData);
        }
        return newData;
    }
    
    /**
     * @param generators            the data generators
     * @return                      Returns a map of generated data keyed by full path
     * 
     * @since 3.2
     */
    private Map<String, Serializable> generateData(Map<String, DataGenerator> generators)
    {
        Map<String, Serializable> newData = new HashMap<String, Serializable>(generators.size() + 5);
        for (Map.Entry<String, DataGenerator> entry : generators.entrySet())
        {
            String path = entry.getKey();
            DataGenerator generator = entry.getValue();
            final Serializable data;
            try
            {
                data = generator.getData();
            }
            catch (Throwable e)
            {
                throw new AlfrescoRuntimeException(
                        "Failed to generate audit data: \n" +
                        "   Path:      " + path + "\n" +
                        "   Generator: " + generator,
                        e);
            }
            // Add it to the map
            newData.put(path, data);
        }
        // Done
        return newData;
    }

    /**
     * {@inheritDoc}
     */
    public void auditQuery(AuditQueryCallback callback, AuditQueryParameters parameters, int maxResults)
    {
        ParameterCheck.mandatory("callback", callback);
        ParameterCheck.mandatory("parameters", parameters);
        
        // Shortcuts
        if (parameters.isZeroResultQuery())
        {
            return;
        }
        
        auditDAO.findAuditEntries(callback, parameters, maxResults);
    }
}
