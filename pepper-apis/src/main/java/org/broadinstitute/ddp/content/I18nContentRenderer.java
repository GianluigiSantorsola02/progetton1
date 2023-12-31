package org.broadinstitute.ddp.content;

import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.db.TemplateVariableDao;
import org.broadinstitute.ddp.db.dao.JdbiLanguageCode;
import org.broadinstitute.ddp.db.dao.TemplateDao;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class I18nContentRenderer {

    public static final String DEFAULT_LANGUAGE_CODE = "en";

    private static final Logger LOG = LoggerFactory.getLogger(I18nContentRenderer.class);
    private static final String TEMPLATE_NAME = "testTemplate";
    private static final String STRING_RESOURCE_LOADER_CLASS_PROP = "string.resource.loader.class";
    private static final String STRING_RESOURCE_LOADER_REPOSITORY_STATIC_PROP
            = "string.resource.loader.repository.static";

    private static Long defaultLangId = null;

    private TemplateVariableDao templateVariableDao;
    private VelocityEngine engine;

    private static Long getDefaultLanguageId(Handle handle) {
        if (defaultLangId != null) {
            return defaultLangId;
        } else {
            defaultLangId = handle.attach(JdbiLanguageCode.class).getLanguageCodeId(DEFAULT_LANGUAGE_CODE);
            return defaultLangId;
        }
    }

    public I18nContentRenderer() {
        Config sqlConfig = ConfigFactory.load(ConfigFile.SQL_CONFIG_FILE);
        templateVariableDao = new TemplateVariableDao(sqlConfig
                .getString(ConfigFile.GET_TEMPLATE_VARIABLES_WITH_TRANSLATIONS_QUERY));
        engine = createVelocityEngine();
    }

    /**
     * Creates a VelocityEngine object responsible for rendering templates
     * and sets certain properties.
     *
     * @return An initialized VelocityEngine instance
     */
    private VelocityEngine createVelocityEngine() {
        VelocityEngine engine = new VelocityEngine();
        engine.setProperty(Velocity.RESOURCE_LOADER, "string");
        engine.addProperty(STRING_RESOURCE_LOADER_CLASS_PROP, StringResourceLoader.class.getName());
        engine.addProperty(STRING_RESOURCE_LOADER_REPOSITORY_STATIC_PROP, "false");
        engine.init();
        return engine;
    }

    /**
     * Renders the template content into final html. Uses a default fallback language.
     *
     * @param handle            JDBC connection
     * @param contentTemplateId Id of the template to render
     * @param languageCodeId    Language to fetch translations for
     * @return A string containting the template rendered into html
     * @throws IllegalArgumentException Thrown when one of the method arguments is null
     * @throws NoSuchElementException   Thrown when a db search returns no element
     */
    public String renderContent(Handle handle, Long contentTemplateId, Long languageCodeId) {
        return renderContent(handle, contentTemplateId, languageCodeId, getDefaultLanguageId(handle));
    }

    /**
     * Render look up template from from givent template Id and render it applying variable map values given
     * followed up by the language specific variables associated with the template.
     *
     * @param handle                JDBC connection
     * @param templateId     Id of the template to render
     * @param defaultLanguageCodeId The fallback language if no translations are found for the languageCodeId
     * @param varNameToValueMap Map of variable names to values to be applied to template
     * @return  the rendered template
     * @throws IllegalArgumentException Thrown when one of the method arguments is null
     * @throws NoSuchElementException   Thrown when a db search returns no element
     */
    public String renderContent(Handle handle, Long templateId, Long defaultLanguageCodeId,
                                Map<String, ?> varNameToValueMap) {
        Map<String, String> varNameToString = new HashMap<>();
        for (Map.Entry<String, ?> entry : varNameToValueMap.entrySet()) {
            varNameToString.put(entry.getKey(), convertToString(entry.getValue()));
        }
        return render(handle, templateId, getDefaultLanguageId(handle), defaultLanguageCodeId, varNameToString);
    }

    /**
     * Renders the template content into final html.
     *
     * @param handle                JDBC connection
     * @param contentTemplateId     Id of the template to render
     * @param languageCodeId        Language to fetch translations for
     * @param defaultLanguageCodeId The fallback language if no translations are found for the languageCodeId
     * @return A string containing the template rendered into html
     * @throws IllegalArgumentException Thrown when one of the method arguments is null
     * @throws NoSuchElementException   Thrown when a db search returns no element
     */
    public String renderContent(Handle handle, Long contentTemplateId, Long languageCodeId, Long defaultLanguageCodeId) {
        return render(handle, contentTemplateId, languageCodeId, defaultLanguageCodeId, Collections.emptyMap());
    }

    private String render(Handle handle, Long contentTemplateId, Long languageCodeId, Long defaultLanguageCodeId,
                         Map<String, String> varNameToValueMap) {

        if (contentTemplateId == null) {
            throw new IllegalArgumentException("contentTemplateId cannot be null");
        }

        if (languageCodeId == null) {
            throw new IllegalArgumentException("languageCodeId cannot be null");
        }

        Optional<TemplateDao.TextAndVarCount> res = handle.attach(TemplateDao.class).findTextAndVarCountById(contentTemplateId);
        if (!res.isPresent() || res.get().getText() == null) {
            throw new NoSuchElementException("could not find template with id " + contentTemplateId);
        }
        String templateText = res.get().getText();
        int templateVariableCount = res.get().getVarCount();

        Map<String, String> translatedTemplateVariables = null;
        List<Long> languageCodeIds = Arrays.asList(languageCodeId, defaultLanguageCodeId);
        boolean allVariablesTranslated = false;

        for (Long langCodeId : languageCodeIds) {
            LOG.info("Fetching translations for the variables with templateId " + contentTemplateId);
            translatedTemplateVariables
                    = templateVariableDao.getTranslatedTemplateVariablesByTemplateIdAndLanguageCodeId(
                    handle,
                    contentTemplateId,
                    langCodeId
            );
            int translatedTemplateVariablesCount = translatedTemplateVariables.size();
            if (templateVariableCount == translatedTemplateVariablesCount) {
                LOG.info("Found all required translations for variables with templateId " + contentTemplateId);
                allVariablesTranslated = true;
                break;
            } else if (templateVariableCount != translatedTemplateVariablesCount) {
                if (translatedTemplateVariablesCount == 0) {
                    LOG.warn("No translations for the language with id "
                            + langCodeId + ", trying the next language");
                } else {
                    String errMsg = "The template with id " + contentTemplateId + " is only partially translated: "
                            + templateVariableCount + " variable(s) exist while only "
                            + translatedTemplateVariablesCount + " are translated, " + "trying the next language";
                    LOG.warn(errMsg);
                }
            }
        }

        if (!allVariablesTranslated) {
            LOG.warn("The template with id " + contentTemplateId
                    + " is not completely translated, some variables won't be substituted!");
        }

        Map<String, String> allVariables = new HashMap<>(varNameToValueMap);
        if (translatedTemplateVariables != null) {
            allVariables.putAll(translatedTemplateVariables);
        }

        return renderToString(templateText, allVariables);
    }

    /**
     * Given the language, render all of the templates.
     *
     * @param handle      the database handle
     * @param templateIds the set of template ids
     * @param langCodeId  the language code id
     * @return mapping of template id to its rendered template string
     * @throws NoSuchElementException when any one of the templates is not found
     */
    public Map<Long, String> bulkRender(Handle handle, Set<Long> templateIds, long langCodeId) {
        if (templateIds == null || templateIds.isEmpty()) {
            return new HashMap<>();
        }

        TemplateDao tmplDao = handle.attach(TemplateDao.class);
        Map<Long, TemplateDao.TextAndVarCount> templateData = tmplDao.findAllTextAndVarCountsByIds(templateIds);
        Map<Long, Map<String, String>> variables = tmplDao.findAllTranslatedVariablesByIds(templateIds, langCodeId);
        Map<Long, String> rendered = new HashMap<>(templateIds.size());

        for (long templateId : templateIds) {
            TemplateDao.TextAndVarCount data = templateData.get(templateId);
            if (data == null || data.getText() == null) {
                throw new NoSuchElementException("Could not find template text with id " + templateId);
            }

            Map<String, String> vars = variables.getOrDefault(templateId, new HashMap<>());
            if (vars.size() != data.getVarCount()) {
                LOG.warn("Not all variables will be translated for template id {} (found {}/{})",
                        templateId, vars.size(), data.getVarCount());
            }

            rendered.put(templateId, renderToString(data.getText(), vars));
        }

        return rendered;
    }

    /**
     * Render all the templates for, and apply them to, the given collection.
     *
     * @param handle      the database handle
     * @param renderables the collection of things to render
     * @param style       the content style to format rendered templates
     * @param langCodeId  the language code id
     * @param <T>         the type of object that needs rendering
     */
    public <T extends Renderable> void bulkRenderAndApply(Handle handle, Collection<T> renderables, ContentStyle style, long langCodeId) {
        Set<Long> templateIds = new HashSet<>();

        for (Renderable renderable : renderables) {
            renderable.registerTemplateIds(templateIds::add);
        }

        Map<Long, String> rendered = bulkRender(handle, templateIds, langCodeId);
        Renderable.Provider<String> provider = rendered::get;

        for (Renderable renderable : renderables) {
            renderable.applyRenderedTemplates(provider, style);
        }
    }

    public String renderToString(String template, Map<String, String> context) {
        StringResourceRepository repo = (StringResourceRepository) engine
                .getApplicationAttribute(StringResourceLoader.REPOSITORY_NAME_DEFAULT);
        repo.putStringResource(TEMPLATE_NAME, template);

        VelocityContext ctx = new VelocityContext(context);
        Template tmpl = engine.getTemplate(TEMPLATE_NAME);

        StringWriter writer = new StringWriter();
        tmpl.merge(ctx, writer);

        return writer.toString();
    }


    private String convertToString(Object obj) {
        //todo can make this conversion a little more sophisticated, e.g. different date formats for different locales or langCodes
        if (obj instanceof LocalDate) {
            return DateTimeFormatter.ofPattern("MMMM dd, yyyy").format((LocalDate) obj);
        } else {
            return obj.toString();
        }

    }

}
