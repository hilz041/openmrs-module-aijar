/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 * <p/>
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 * <p/>
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.aijar;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.*;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.LocationService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleActivator;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.aijar.activator.AppConfigurationInitializer;
import org.openmrs.module.aijar.activator.HtmlFormsInitializer;
import org.openmrs.module.aijar.activator.Initializer;
import org.openmrs.module.aijar.api.deploy.bundle.CommonMetadataBundle;
import org.openmrs.module.aijar.api.deploy.bundle.UgandaAddressMetadataBundle;
import org.openmrs.module.aijar.metadata.core.PatientIdentifierTypes;
import org.openmrs.module.appframework.service.AppFrameworkService;
import org.openmrs.module.emrapi.EmrApiConstants;
import org.openmrs.module.emrapi.utils.MetadataUtil;
import org.openmrs.module.idgen.IdentifierSource;
import org.openmrs.module.idgen.service.IdentifierSourceService;
import org.openmrs.module.metadatadeploy.api.MetadataDeployService;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.notification.AlertService;
import org.openmrs.ui.framework.resource.ResourceFactory;
import org.openmrs.util.OpenmrsUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This class contains the logic that is run every time this module is either started or stopped.
 * <p>
 * TODO: Refactor the whole class to use initializers like
 */
public class AijarActivator extends org.openmrs.module.BaseModuleActivator {

    protected Log log = LogFactory.getLog(getClass());

    /**
     * @see ModuleActivator#willRefreshContext()
     */
    public void willRefreshContext() {
        log.info("Refreshing aijar Module");
    }

    /**
     * @see ModuleActivator#contextRefreshed()
     */
    public void contextRefreshed() {
        log.info("aijar Module refreshed");
    }

    /**
     * @see ModuleActivator#willStart()
     */
    public void willStart() {
        log.info("Starting aijar Module");
    }

    /**
     * @see ModuleActivator#started()
     */
    public void started() {
        AdministrationService administrationService = Context.getAdministrationService();
        AppFrameworkService appFrameworkService = Context.getService(AppFrameworkService.class);
        MetadataDeployService deployService = Context.getService(MetadataDeployService.class);
        ConceptService conceptService = Context.getConceptService();
        LocationService locationService = Context.getLocationService();

        try {
            // disable the reference app registration page
            appFrameworkService.disableApp("referenceapplication.registrationapp.registerPatient");
            // disable the start visit app since all data is retrospective
            appFrameworkService.disableExtension("org.openmrs.module.coreapps.createVisit");
            // the extension to the edit person details
            appFrameworkService.disableExtension("org.openmrs.module.registrationapp.editPatientDemographics");

            // form entry app on the home page
            appFrameworkService.disableApp("xforms.formentry");
            // form entry extension in active visits
            appFrameworkService.disableExtension("xforms.formentry.cfpd");

            // run the initializers
            for (Initializer initializer : getInitializers()) {
                initializer.started();
            }

            // install commonly used metadata
            installCommonMetadata(deployService);

            // save defined global properties
            administrationService.saveGlobalProperties(configureGlobalProperties());

            // update the name of the default health center with that stored in the global property
            Location healthCenter = locationService.getLocationByUuid("629d78e9-93e5-43b0-ad8a-48313fd99117");
            healthCenter.setName(administrationService.getGlobalProperty(AijarConstants.GP_HEALTH_CENTER_NAME));
            locationService.saveLocation(healthCenter);

            // cleanup liquibase change logs to enable installation of data integrity module
            removeOldChangeLocksForDataIntegrityModule();

            // generate OpenMRS ID for patients without the identifier
            generateOpenMRSIdentifierForPatientsWithout();


        } catch (Exception e) {
            Module mod = ModuleFactory.getModuleById("aijar");
            ModuleFactory.stopModule(mod);
            throw new RuntimeException("failed to setup the module ", e);
        }

        log.info("aijar Module started");
    }

    /**
     * Generate patientIdentifier for old OpenMRS Migration to the new
     * */

    protected PatientIdentifier generatePatientIdentifier() {
        IdentifierSourceService iss = Context.getService(IdentifierSourceService.class);
        IdentifierSource idSource = iss.getIdentifierSource(1); // this is the default OpenMRS identifier source
        PatientService patientService = Context.getPatientService();

        UUID uuid = UUID.randomUUID();

        PatientIdentifierType patientIdentifierType = patientService.getPatientIdentifierTypeByUuid("05a29f94-c0ed-11e2-94be-8c13b969e334");

        PatientIdentifier pid = new PatientIdentifier();
        pid.setIdentifierType(patientIdentifierType);
        String identifier = iss.generateIdentifier(idSource, "New OpenMRS ID with CheckDigit");
        pid.setIdentifier(identifier);
        pid.setPreferred(true);
        pid.setUuid(String.valueOf(uuid));

        return pid;

    }

    /**
     * Generate an OpenMRS ID for patients who do not have one due to a migration from an old OpenMRS ID to a new one which contains a check-digit
     **/
    private void generateOpenMRSIdentifierForPatientsWithout() {
        PatientService patientService = Context.getPatientService();
        AdministrationService as = Context.getAdministrationService();
        AlertService alertService = Context.getAlertService();

        List<List<Object>> patientIds = as.executeSQL("SELECT patient_id FROM patient_identifier WHERE patient_id NOT IN (SELECT patient_id FROM patient_identifier p INNER JOIN patient_identifier_type pt ON (p.identifier_type = pt.patient_identifier_type_id AND pt.uuid = '05a29f94-c0ed-11e2-94be-8c13b969e334'))", true);

        if (patientIds.size() == 0) {
            // no patients to process
            return;
        }
        // get the identifier source copied from RegistrationCoreServiceImpl

        for (List<Object> row : patientIds) {
            Patient p = patientService.getPatient((Integer) row.get(0));
            // Create new Patient Identifier
            PatientIdentifier pid = generatePatientIdentifier();
            pid.setPatient(p);
            try {
                log.info("Adding OpenMRS ID " + pid.getIdentifier() + " to patient with id " + p.getPatientId());
                // Save the patient Identifier
                patientService.savePatientIdentifier(pid);
            } catch (Exception e) {
                // log the error to the alert service but do not rethrow the exception since the module has to start
                log.error("Error updating OpenMRS identifier for patient #" + p.getPatientId(), e);
            }
        }
        log.info("All patients updated with new OpenMRS ID");
    }

    /**
     * Configure the global properties for the expected functionality
     *
     * @return
     */
    private List<GlobalProperty> configureGlobalProperties() {
        List<GlobalProperty> properties = new ArrayList<GlobalProperty>();
        // The National ID as the primary identifier that needs to be displayed
        properties.add(
                new GlobalProperty(EmrApiConstants.PRIMARY_IDENTIFIER_TYPE, PatientIdentifierTypes.NATIONAL_ID.uuid()));
        
        String ART_Patient_Number_Identifier = "";
        // check if the ART patient number is to be displayed then add it here
        if (Context.getAdministrationService().getGlobalProperty("ugandaemr.showARTPatientNumberIdentifier").equals("true")) {
            log.info("Adding ART patient number to extra identifier types property");
            ART_Patient_Number_Identifier = "," + PatientIdentifierTypes.ART_PATIENT_NUMBER.uuid();
        }

        String Research_Patient_Identifier = "";
        // check if the ART patient number is to be displayed then add it here
        if (Context.getAdministrationService().getGlobalProperty("ugandaemr.showResearchPatientIdentifier").equals("true")) {
            log.info("Adding research atient number to extra identifier types property");
            Research_Patient_Identifier = "," + PatientIdentifierTypes.RESEARCH_PATIENT_ID.uuid();
        }

        // set the HIV care number and EID number as additional identifiers that can be searched for
        properties.add(new GlobalProperty(EmrApiConstants.GP_EXTRA_PATIENT_IDENTIFIER_TYPES,
                PatientIdentifierTypes.HIV_CARE_NUMBER.uuid() + "," + PatientIdentifierTypes.EXPOSED_INFANT_NUMBER.uuid()
                        + "," + PatientIdentifierTypes.IPD_NUMBER.uuid() + "," + PatientIdentifierTypes.ANC_NUMBER.uuid()
                        + "," + PatientIdentifierTypes.HCT_NUMBER.uuid() + ART_Patient_Number_Identifier + Research_Patient_Identifier));

        // set the name of the application
        properties.add(new GlobalProperty("application.name", "UgandaEMR - Uganda eHealth Solution"));

        // Remove the regular expression to validate names
        properties.add(new GlobalProperty("patient.nameValidationRegex", ""));

        // the search mode for patients to enable searching any part of names rather than the beginning
        properties.add(new GlobalProperty("patientSearch.matchMode", "ANYWHERE"));

        // enable searching on parts of the patient identifier
        // the prefix and suffix provide a % round the entered search term with a like
        properties.add(new GlobalProperty("patient.identifierPrefix", "%"));
        properties.add(new GlobalProperty("patient.identifierSuffix", "%"));

        // the RegeX and Search patterns should be empty so that the prefix and suffix matching above can work
        properties.add(new GlobalProperty("patient.identifierRegex", ""));
        properties.add(new GlobalProperty("patient.identifierSearchPattern", ""));

        // Form Entry Settings
        properties.add(new GlobalProperty("FormEntry.enableDashboardTab", "true"));     // show as a tab on the patient dashboard
        properties.add(new GlobalProperty("FormEntry.FormEntry.enableOnEncounterTab", "true"));

        //HTML Form Entry Settings
        properties.add(new GlobalProperty("htmlformentry.showDateFormat", "false"));//Disable date format display on form entry

        //Birt Settings
        properties.add(new GlobalProperty("birt.alwaysUseOpenmrsJdbcProperties", "false"));
        properties.add(new GlobalProperty("birt.birtHome", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "birt-runtime-2_3_2" + File.separator + "ReportEngine"));
        properties.add(new GlobalProperty("birt.datasetDir", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "reports" + File.separator + "datasets"));
        properties.add(new GlobalProperty("birt.loggingDir", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "logs"));
        properties.add(new GlobalProperty("birt.defaultReportDesignFile", "default.rptdesign"));
        properties.add(new GlobalProperty("birt.loggingLevel", "OFF"));
        properties.add(new GlobalProperty("birt.mandatory", "false"));
        properties.add(new GlobalProperty("birt.outputDir", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "reports" + File.separator + "output"));
        properties.add(new GlobalProperty("birt.reportDir", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "reports"));
        properties.add(new GlobalProperty("birt.reportOutputFile", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "reports" + File.separator + "output" + File.separator + "ReportOutput.pdf"));
        properties.add(new GlobalProperty("birt.reportOutputFormat", "pdf"));
        properties.add(new GlobalProperty("birt.reportPreviewFile", OpenmrsUtil.getApplicationDataDirectory() + "birt" + File.separator + "reports" + File.separator + "output" + File.separator + "ReportPreview.pdf"));

        // disable the appointmentshedulingui which currently has issues
        properties.add(new GlobalProperty("appointmentschedulingui.started", "false"));

        // Exclude temporary reporting tables by database backup module
        properties.add(new GlobalProperty("databasebackup.tablesExcluded", "aijar_105_eid,aijar_106a1a"));

        return properties;
    }

    private void installCommonMetadata(MetadataDeployService deployService) {
        try {
            log.info("Installing metadata");
            log.info("Installing commonly used metadata");
            deployService.installBundle(Context.getRegisteredComponents(CommonMetadataBundle.class).get(0));
            log.info("Finished installing commonly used metadata");
            log.info("Installing address hierarchy");
            deployService.installBundle(Context.getRegisteredComponents(UgandaAddressMetadataBundle.class).get(0));
            log.info("Finished installing addresshierarchy");

            // retire concepts that are duplicated in the
            // concept metadata package
            /*ConceptService conceptService = Context.getConceptService();
            List<String> conceptsToRetire = Arrays.asList("8b64f9e1-196a-4802-a287-fd160fb97002", // YES
			        "b1629d9a-91a5-4895-b6bc-647f3a944534" // NO
			        , "1065AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // YES
			        , "1066AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // NO
			        , "1067AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // UNKNOWN
			        , "1499AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // MODERATE
			        , "1500AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // SEVERE
			        , "1734AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // YEARS
			        , "111633AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // URINARY TRACT INFECTION
			        , "117543AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // HERPES ZOSTER
	        );
	        for (String uuid : conceptsToRetire) {
		        Concept concept = conceptService.getConceptByUuid(uuid);
		        if (concept != null) {
			        // retire the concept
			        log.info("Retiring concept " + concept.toString());
			        conceptService.retireConcept(concept, "Duplicated in MDS import");
			        log.info("Retired concept " + concept.toString());
		        }
	        }*/

            // install concepts
            log.info("Installing standard metadata using the packages.xml file");
            MetadataUtil.setupStandardMetadata(getClass().getClassLoader());
            log.info("Standard metadata installed");

        } catch (Exception e) {
            Module mod = ModuleFactory.getModuleById("aijar");
            ModuleFactory.stopModule(mod);
            throw new RuntimeException("failed to install the common metadata ", e);
        }
    }

    private void removeOldChangeLocksForDataIntegrityModule() {
        String gpVal = Context.getAdministrationService().getGlobalProperty("dataintegrity.database_version");
        if (ObjectUtil.isNull(gpVal)) {
            AdministrationService as = Context.getAdministrationService();
            log.warn("Removing liquibase change log locks for previously installed data integrity instance");
            as.executeSQL("delete from liquibasechangelog WHERE ID like 'dataintegrity%';", false);
        }
    }

    /**
     * @see ModuleActivator#willStop()
     */
    public void willStop() {
        log.info("Stopping aijar Module");
    }

    /**
     * @see ModuleActivator#stopped()
     */
    public void stopped() {

        log.info("aijar Module stopped");
    }

    public List<Initializer> getInitializers() {
        List<Initializer> l = new ArrayList<Initializer>();
        l.add(new AppConfigurationInitializer());
        l.add(new HtmlFormsInitializer());
        return l;
    }
}