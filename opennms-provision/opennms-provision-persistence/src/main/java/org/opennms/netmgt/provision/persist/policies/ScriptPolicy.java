/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.provision.persist.policies;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.provision.BasePolicy;
import org.opennms.netmgt.provision.NodePolicy;
import org.opennms.netmgt.provision.annotations.Policy;
import org.opennms.netmgt.provision.annotations.Require;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.google.common.io.Files;

@Component
/**
 * <p>ScriptPolicy class.</p>
 *
 * @author cpape
 */
@Scope("prototype")
@Policy("Script Policy")
public class ScriptPolicy extends BasePolicy<OnmsNode> implements NodePolicy {
    private static final Logger LOG = LoggerFactory.getLogger(ScriptPolicy.class);

    private Path m_scriptPath;
    private final ScriptEngineManager m_scriptManager = new ScriptEngineManager();
    private long m_lastCompiled = -1;
    private String m_script;
    private CompiledScript m_compiledScript;

    public ScriptPolicy() {
        this(Paths.get(System.getProperty("opennms.home"), "etc", "script-policies"));
    }

    public ScriptPolicy(final Path scriptPath) {
        this.m_scriptPath = scriptPath;
    }

    private CompiledScript compileScript(final String script) throws IOException, ScriptException {
        if (script == null) {
            throw new IllegalArgumentException("Script must not be null");
        }

        final File scriptFile = m_scriptPath.resolve(script).toFile();

        if (!scriptFile.canRead()) {
            throw new IllegalStateException("Cannot read script at '" + scriptFile + "'.");
        }

        if (scriptFile.lastModified() > m_lastCompiled) {
            final String fileExtension = Files.getFileExtension(scriptFile.getAbsolutePath());

            final ScriptEngine engine = m_scriptManager.getEngineByExtension(fileExtension);
            if (engine == null) {
                throw new IllegalStateException("No engine found for file extension: " + fileExtension);
            }

            if (!(engine instanceof Compilable)) {
                throw new IllegalStateException("Only engines that can compile scripts are supported.");
            }
            final Compilable compilable = (Compilable) engine;
            try (FileReader reader = new FileReader(scriptFile)) {
                m_compiledScript = compilable.compile(reader);
            }
            m_lastCompiled = scriptFile.lastModified();
        }

        return m_compiledScript;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OnmsNode act(final OnmsNode node) {
        try {
            final CompiledScript compiledScript = compileScript(getScript());

            if (compiledScript == null) {
                LOG.warn("No compiled script available for execution.");
            } else {
                final SimpleBindings globals = new SimpleBindings();
                globals.put("node", node);
                globals.put("LOG", LOG);
                return (OnmsNode) compiledScript.eval(globals);
            }
        } catch (Exception ex) {
            LOG.warn("Error applying ScriptPolicy.", ex);
        }

        return node;
    }

    /**
     * <p>getScript</p>
     *
     * @return a {@link java.lang.String} object.
     */
    @Require(value = {})
    public String getScript() {
        return m_script;
    }

    /**
     * <p>setScript</p>
     *
     * @param script a {@link java.lang.String} object.
     */
    public void setScript(String script) {
        m_script = script;
    }

    /**
     * <p>getType</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getType() {
        return getCriteria("type");
    }

    /**
     * <p>setType</p>
     *
     * @param type a {@link java.lang.String} object.
     */
    public void setType(String type) {
        putCriteria("type", type);
    }

    /**
     * <p>getSysObjectId</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSysObjectId() {
        return getCriteria("sysObjectId");
    }

    /**
     * <p>setSysObjectId</p>
     *
     * @param sysObjectId a {@link java.lang.String} object.
     */
    public void setSysObjectId(String sysObjectId) {
        putCriteria("sysObjectId", sysObjectId);
    }

    /**
     * <p>getSysName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSysName() {
        return getCriteria("sysName");
    }

    /**
     * <p>setSysName</p>
     *
     * @param sysName a {@link java.lang.String} object.
     */
    public void setSysName(String sysName) {
        putCriteria("sysName", sysName);
    }

    /**
     * <p>getSysDescription</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSysDescription() {
        return getCriteria("sysDescription");
    }

    /**
     * <p>setSysDescription</p>
     *
     * @param sysDescription a {@link java.lang.String} object.
     */
    public void setSysDescription(String sysDescription) {
        putCriteria("sysDescription", sysDescription);
    }

    /**
     * <p>getSysLocation</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSysLocation() {
        return getCriteria("sysLocation");
    }

    /**
     * <p>setSysLocation</p>
     *
     * @param sysLocation a {@link java.lang.String} object.
     */
    public void setSysLocation(String sysLocation) {
        putCriteria("sysLocation", sysLocation);
    }

    /**
     * <p>getSysContact</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSysContact() {
        return getCriteria("sysContact");
    }

    /**
     * <p>setSysContact</p>
     *
     * @param sysContact a {@link java.lang.String} object.
     */
    public void setSysContact(String sysContact) {
        putCriteria("sysContact", sysContact);
    }

    /**
     * <p>getLabel</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getLabel() {
        return getCriteria("label");
    }

    /**
     * <p>setLabel</p>
     *
     * @param label a {@link java.lang.String} object.
     */
    public void setLabel(String label) {
        putCriteria("label", label);
    }

    /**
     * <p>getLabelSource</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getLabelSource() {
        return getCriteria("labelSource");
    }

    /**
     * <p>setLabelSource</p>
     *
     * @param labelSource a {@link java.lang.String} object.
     */
    public void setLabelSource(String labelSource) {
        putCriteria("labelSource", labelSource);
    }

    /**
     * <p>getNetBiosName</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getNetBiosName() {
        return getCriteria("netBiosName");
    }

    /**
     * <p>setNetBiosName</p>
     *
     * @param netBiosName a {@link java.lang.String} object.
     */
    public void setNetBiosName(String netBiosName) {
        putCriteria("netBiosName", netBiosName);
    }

    /**
     * <p>getNetBiosDomain</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getNetBiosDomain() {
        return getCriteria("netBiosDomain");
    }

    /**
     * <p>setNetBiosDomain</p>
     *
     * @param netBiosDomain a {@link java.lang.String} object.
     */
    public void setNetBiosDomain(String netBiosDomain) {
        putCriteria("netBiosDomain", netBiosDomain);
    }

    /**
     * <p>getOperatingSystem</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getOperatingSystem() {
        return getCriteria("operatingSystem");
    }

    /**
     * <p>setOperatingSystem</p>
     *
     * @param operatingSystem a {@link java.lang.String} object.
     */
    public void setOperatingSystem(String operatingSystem) {
        putCriteria("operatingSystem", operatingSystem);
    }

    /**
     * <p>getForeignId</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getForeignId() {
        return getCriteria("foreignId");
    }

    /**
     * <p>setForeignId</p>
     *
     * @param foreignId a {@link java.lang.String} object.
     */
    public void setForeignId(String foreignId) {
        putCriteria("foreignId", foreignId);
    }

    /**
     * <p>getForeignSource</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getForeignSource() {
        return getCriteria("foreignSource");
    }

    /**
     * <p>setForeignSource</p>
     *
     * @param foreignSource a {@link java.lang.String} object.
     */
    public void setForeignSource(String foreignSource) {
        putCriteria("foreignSource", foreignSource);
    }
}
