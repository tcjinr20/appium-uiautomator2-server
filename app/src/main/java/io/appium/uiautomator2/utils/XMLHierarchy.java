/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.uiautomator2.utils;

import android.view.accessibility.AccessibilityNodeInfo;

import org.apache.commons.lang.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.HashMap;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import io.appium.uiautomator2.common.exceptions.UiAutomator2Exception;
import io.appium.uiautomator2.core.AccessibilityNodeInfoDumper;

import static io.appium.uiautomator2.utils.AXWindowHelpers.currentActiveWindowRoot;
import static io.appium.uiautomator2.utils.AXWindowHelpers.refreshRootAXNode;

public abstract class XMLHierarchy {
    // XML 1.0 Legal Characters (http://stackoverflow.com/a/4237934/347155)
    // #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
    private final static Pattern XML10Pattern = Pattern.compile("[^" + "\u0009\r\n" +
            "\u0020-\uD7FF" + "\uE000-\uFFFD" + "\ud800\udc00-\udbff\udfff" + "]");
    private final static String DEFAULT_VIEW_NAME = "android.view.View";

    public static InputSource getRawXMLHierarchy() throws UiAutomator2Exception {
        refreshRootAXNode();
        return getRawXMLHierarchy(currentActiveWindowRoot());
    }

    private static InputSource getRawXMLHierarchy(AccessibilityNodeInfo root) throws UiAutomator2Exception {
        String xmlDump = AccessibilityNodeInfoDumper.getWindowXMLHierarchy(root);
        return new InputSource(new StringReader(xmlDump));
    }

    public static Node getFormattedXMLDoc() throws UiAutomator2Exception {
        return formatXMLInput(getRawXMLHierarchy());
    }

    private static Node formatXMLInput(InputSource input) {
        XPath xpath = XPathFactory.newInstance().newXPath();

        final Node root;
        try {
            root = (Node) xpath.evaluate("/", input, XPathConstants.NODE);
        } catch (XPathExpressionException e) {
            throw new UiAutomator2Exception("Could not read xml hierarchy: ", e);
        }

        HashMap<String, Integer> instances = new HashMap<>();

        // rename all the nodes with their "class" attribute
        // add an instance attribute
        annotateNodes(root, instances);

        return root;
    }

    private static void annotateNodes(Node node, HashMap<String, Integer> instances) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                visitNode(children.item(i), instances);
                annotateNodes(children.item(i), instances);
            }
        }
    }

    // set the node's tag name to the same as it's android class.
    // also number all instances of each class with an "instance" number. It increments
    // for each class separately.
    // this allows use to use class and instance to identify a node.
    // we also take this chance to clean class names that might have dollar signs in
    // them (and other odd characters)
    private static void visitNode(Node node, HashMap<String, Integer> instances) {
        Document doc = node.getOwnerDocument();
        NamedNodeMap attributes = node.getAttributes();

        String androidClass;
        try {
            androidClass = attributes.getNamedItem("class").getNodeValue();
        } catch (Exception e) {
            return;
        }

        androidClass = cleanTagName(androidClass);

        if (!instances.containsKey(androidClass)) {
            instances.put(androidClass, 0);
        }
        Integer instance = instances.get(androidClass);

        Node attrNode = doc.createAttribute("instance");
        attrNode.setNodeValue(instance.toString());
        attributes.setNamedItem(attrNode);

        doc.renameNode(node, node.getNamespaceURI(), androidClass);

        instances.put(androidClass, instance + 1);
    }

    private static String cleanTagName(String name) {
        if (StringUtils.isBlank(name)) {
            return DEFAULT_VIEW_NAME;
        }

        String fixedName = name
                .replaceAll("[$@#&]", ".")
                // https://github.com/appium/appium/issues/9934
                .replaceAll("[ˋˊ\\s]", ""); // "ˋ" is \xCB\x8B in UTF-8
        fixedName = safeCharSeqToString(fixedName)
                // https://github.com/appium/appium/issues/9934
                .replace("?", "")
                .replaceAll("\\.+", ".")
                .replaceAll("(^\\.|\\.$)", "");
        if (!fixedName.equals(name)) {
            Logger.info(String.format("Rewrote XML tag name '%s' to '%s'", name, fixedName));
        }
        return StringUtils.isBlank(fixedName) ? DEFAULT_VIEW_NAME : fixedName;
    }

    public static String safeCharSeqToString(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        return XML10Pattern.matcher(String.valueOf(cs)).replaceAll("?");
    }
}
