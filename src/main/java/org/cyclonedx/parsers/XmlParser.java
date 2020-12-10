/*
 * This file is part of CycloneDX Core (Java).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.cyclonedx.parsers;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.converters.enums.EnumToStringConverter;
import com.thoughtworks.xstream.io.xml.QNameMap;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import com.thoughtworks.xstream.mapper.CannotResolveClassException;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.converters.DateConverter;
import org.cyclonedx.converters.HashConverter;
import org.cyclonedx.converters.AttachmentTextConverter;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Commit;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Component.Scope;
import org.cyclonedx.model.Dependency;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalContact;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Pedigree;
import org.cyclonedx.model.Swid;
import org.cyclonedx.model.Tool;
import org.cyclonedx.util.XStreamUtils;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * XmlParser is responsible for validating and parsing CycloneDX bill-of-material
 * XML documents and returning a {@link Bom} object.
 * @since 3.0.0
 */
@SuppressWarnings("unused")
public class XmlParser extends CycloneDxSchema implements Parser {

    /**
     * {@inheritDoc}
     */
    public Bom parse(final File file) throws ParseException {
        try {
            final String schemaVersion = identifySchemaVersion(
                    extractAllNamespaceDeclarations(new InputSource(new FileInputStream(file))));
            final XStream xstream = XStreamUtils.mapObjectModelBom1_2(createXStream());
            final Bom bom = (Bom) xstream.fromXML(file);
            return injectSchemaVersion(bom, schemaVersion);
        } catch (XStreamException | XPathExpressionException | FileNotFoundException e) {
            throw new ParseException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Bom parse(final byte[] bomBytes) throws ParseException {
        try {
            final String schemaVersion = identifySchemaVersion(
                    extractAllNamespaceDeclarations(new InputSource(new ByteArrayInputStream(bomBytes))));
            final XStream xstream = XStreamUtils.mapObjectModelBom1_2(createXStream());
            final Bom bom = (Bom)xstream.fromXML(new ByteArrayInputStream(bomBytes));
            return injectSchemaVersion(bom, schemaVersion);
        } catch (XStreamException | XPathExpressionException e) {
            throw new ParseException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Bom parse(final InputStream inputStream) throws ParseException {
        try {
            XStream xstream = XStreamUtils.mapObjectModelBom1_2(createXStream());
            return (Bom) xstream.fromXML(inputStream);
        } catch (XStreamException e) {
            throw new ParseException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Bom parse(final Reader reader) throws ParseException {
        try {
            XStream xstream = XStreamUtils.mapObjectModelBom1_2(createXStream());
            return (Bom) xstream.fromXML(reader);
        } catch (XStreamException e) {
            throw new ParseException(e);
        }
    }

    /**
     * Uses reflection to set the schemaVersion field inside a Bom instance.
     * The schemaVersion is 'not user serviceable' so no methods for setting
     * it are provided, other than the constructor, which xstream does not
     * use.
     * @param bom the Bom to set the schemaVersion for
     * @param schemaVersion the value of the schema version
     * @return the updated Bom
     * @since 3.0.0
     */
    private Bom injectSchemaVersion(final Bom bom, final String schemaVersion) {
        try {
            final Field field = Bom.class.getDeclaredField("specVersion");
            field.setAccessible(true);
            field.set(bom, schemaVersion);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // throw it away
        }
        return bom;
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final File file) throws IOException {
        return validate(file, CycloneDxSchema.VERSION_LATEST);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final File file, final CycloneDxSchema.Version schemaVersion) throws IOException {
        final Source source = new StreamSource(file);
        return validate(source, schemaVersion);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final byte[] bomBytes) throws IOException {
        return validate(bomBytes, CycloneDxSchema.VERSION_LATEST);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final byte[] bomBytes, final CycloneDxSchema.Version schemaVersion) throws IOException {
        final Source source = new StreamSource(new ByteArrayInputStream(bomBytes));
        return validate(source, schemaVersion);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final Reader reader) throws IOException {
        return validate(reader, CycloneDxSchema.VERSION_LATEST);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final Reader reader, final CycloneDxSchema.Version schemaVersion) throws IOException {
        final Source source = new StreamSource(reader);
        return validate(source, schemaVersion);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final InputStream inputStream) throws IOException {
        return validate(inputStream, CycloneDxSchema.VERSION_LATEST);
    }

    /**
     * {@inheritDoc}
     */
    public List<ParseException> validate(final InputStream inputStream, final CycloneDxSchema.Version schemaVersion) throws IOException {
        final Source source = new StreamSource(inputStream);
        return validate(source, schemaVersion);
    }

    public List<ParseException> validate(final Source source, final CycloneDxSchema.Version schemaVersion) throws IOException {
        final List<ParseException> exceptions = new LinkedList<>();
        try {
            final Schema schema = getXmlSchema(schemaVersion);
            final Validator validator = schema.newValidator();
            validator.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    exceptions.add(new ParseException(e.getMessage(), e));
                }

                @Override
                public void fatalError(SAXParseException e) {
                    exceptions.add(new ParseException(e.getMessage(), e));
                }

                @Override
                public void error(SAXParseException e) {
                    exceptions.add(new ParseException(e.getMessage(), e));
                }
            });
            validator.validate(source);
        } catch (SAXException e) {
            exceptions.add(new ParseException(e.getMessage(), e));
        }
        return exceptions;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final File file) throws IOException {
        return validate(file).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final File file, final CycloneDxSchema.Version schemaVersion) throws IOException {
        return validate(file, schemaVersion).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final byte[] bomBytes) throws IOException {
        return validate(bomBytes).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final byte[] bomBytes, final CycloneDxSchema.Version schemaVersion) throws IOException {
        return validate(bomBytes, schemaVersion).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final Reader reader) throws IOException {
        return validate(reader).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final Reader reader, final CycloneDxSchema.Version schemaVersion) throws IOException {
        return validate(reader, schemaVersion).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final InputStream inputStream) throws IOException {
        return validate(inputStream).isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isValid(final InputStream inputStream, final CycloneDxSchema.Version schemaVersion) throws IOException {
        return validate(inputStream, schemaVersion).isEmpty();
    }

    private String identifySchemaVersion(final NodeList nodeList) {
        for (int i=0; i<nodeList.getLength(); i++) {
            final Node node = nodeList.item(i);
            for (final Version version: Version.values()) {
                if (version.getNamespace().equals(node.getNodeValue()))  {
                    return version.getVersionString();
                }
            }
        }
        return null;
    }

    private NodeList extractAllNamespaceDeclarations(final InputSource in) throws XPathExpressionException {
        final XPathFactory xPathFactory = XPathFactory.newInstance();
        final XPath xPath = xPathFactory.newXPath();
        final XPathExpression xPathExpression = xPath.compile("//namespace::*");
        return (NodeList) xPathExpression.evaluate(in, XPathConstants.NODESET);
    }

    public XStream createXStream() {
        final QName qname = new QName(CycloneDxSchema.NS_BOM_LATEST);
        final QNameMap nsm = new QNameMap();
        nsm.registerMapping(qname, Bom.class);
        nsm.setDefaultNamespace(CycloneDxSchema.NS_BOM_LATEST);
        final XStream xstream = new XStream(new StaxDriver(nsm)) {
            @Override
            protected MapperWrapper wrapMapper(MapperWrapper next) {
                return new MapperWrapper(next) {
                    @Override
                    public boolean shouldSerializeMember(Class definedIn, String fieldName) {
                        try {
                            return definedIn != Object.class || realClass(fieldName) != null;
                        } catch(CannotResolveClassException e) {
                            return false;
                        }
                    }
                    @Override
                    public Class realClass(String elementName) {
                        try {
                            return super.realClass(elementName);
                        } catch(CannotResolveClassException e) {
                            return null;
                        }
                    }
                };
            }
        };
        XStream.setupDefaultSecurity(xstream);
        xstream.allowTypesByWildcard(new String[] {
                "org.cyclonedx.model.**"
        });
        return xstream;
    }
}
