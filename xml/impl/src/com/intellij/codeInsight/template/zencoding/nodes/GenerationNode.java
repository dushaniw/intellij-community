/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.template.zencoding.nodes;

import com.intellij.codeInsight.template.CustomTemplateCallback;
import com.intellij.codeInsight.template.LiveTemplateBuilder;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.zencoding.ZenCodingTemplate;
import com.intellij.codeInsight.template.zencoding.ZenCodingUtil;
import com.intellij.codeInsight.template.zencoding.filters.XmlZenCodingGenerator;
import com.intellij.codeInsight.template.zencoding.filters.XmlZenCodingGeneratorImpl;
import com.intellij.codeInsight.template.zencoding.filters.ZenCodingGenerator;
import com.intellij.codeInsight.template.zencoding.tokens.TemplateToken;
import com.intellij.codeInsight.template.zencoding.tokens.XmlTemplateToken;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.XmlElementFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.util.LocalTimeCounter;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.containers.IntArrayList;
import com.intellij.xml.util.HtmlUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author Eugene.Kudelevsky
 */
public class GenerationNode {
  private final TemplateToken myTemplateToken;
  private final List<GenerationNode> myChildren;
  private final int myNumberInIteration;
  private boolean myToInsertChildren = true;

  public GenerationNode(TemplateToken templateToken, List<GenerationNode> children, int numberInIteration) {
    myTemplateToken = templateToken;
    myChildren = children;
    myNumberInIteration = numberInIteration;
  }

  public void setToInsertChildren(boolean toInsertChildren) {
    myToInsertChildren = toInsertChildren;
  }

  public List<GenerationNode> getChildren() {
    return myChildren;
  }

  public void addChildren(Collection<GenerationNode> child) {
    for (GenerationNode node : child) {
      if (!myToInsertChildren) {
        node.myToInsertChildren = false;
      }
    }
    myChildren.addAll(child);
  }

  public boolean isLeaf() {
    return myChildren.size() == 0;
  }

  public boolean isToInsertChildren() {
    return myToInsertChildren;
  }

  private boolean isBlockTag() {
    if (myTemplateToken instanceof XmlTemplateToken) {
      XmlFile xmlFile = ((XmlTemplateToken)myTemplateToken).getFile();
      XmlDocument document = xmlFile.getDocument();
      if (document != null) {
        XmlTag tag = document.getRootTag();
        if (tag != null) {
          return HtmlUtil.isHtmlBlockTagL(tag.getName());
        }
      }
    }
    return false;
  }

  @NotNull
  public TemplateImpl generate(@NotNull CustomTemplateCallback callback,
                               @Nullable String surroundedText,
                               @Nullable ZenCodingGenerator generator) {
    LiveTemplateBuilder builder = new LiveTemplateBuilder();
    int end = -1;

    boolean hasChildren = myChildren.size() > 0;
    String txt = !hasChildren && myToInsertChildren ? surroundedText : null;

    TemplateImpl parentTemplate = myTemplateToken instanceof XmlTemplateToken ?
                                  invokeXmlTemplate((XmlTemplateToken)myTemplateToken, callback, myNumberInIteration, generator, txt,
                                                    hasChildren) :
                                  invokeTemplate(myTemplateToken, hasChildren, callback, generator, txt);

    int offset = builder.insertTemplate(0, parentTemplate, null);
    int newOffset = gotoChild(callback.getProject(), builder.getText(), offset, 0, builder.length());
    if (offset < builder.length() && newOffset != offset) {
      end = offset;
    }
    offset = newOffset;
    if (end == -1 && offset < builder.length() && myChildren.size() == 0) {
      end = offset;
    }
    LiveTemplateBuilder.Marker marker = offset < builder.length() ? builder.createMarker(offset) : null;
    for (int i = 0, myChildrenSize = myChildren.size(); i < myChildrenSize; i++) {
      GenerationNode child = myChildren.get(i);
      TemplateImpl childTemplate = child.generate(callback, surroundedText, generator);

      boolean blockTag = child.isBlockTag();

      if (blockTag && !isNewLineBefore(builder.getText(), offset)) {
        builder.insertText(offset++, "\n");
      }

      int e = builder.insertTemplate(offset, childTemplate, null);
      offset = marker != null ? marker.getEndOffset() : builder.length();

      if (blockTag && !isNewLineAfter(builder.getText(), offset)) {
        builder.insertText(offset++, "\n");
      }

      if (end == -1 && e < offset) {
        end = e;
      }
    }
    if (end != -1) {
      builder.insertVariableSegment(end, TemplateImpl.END);
    }
    return builder.buildTemplate();
  }

  private static TemplateImpl invokeTemplate(TemplateToken token,
                                             boolean hasChildren,
                                             final CustomTemplateCallback callback,
                                             @Nullable ZenCodingGenerator generator,
                                             String surroundedText) {
    TemplateImpl template = token.getTemplate();
    if (generator != null) {
      assert template != null;
      String s = generator.toString(token, hasChildren, callback.getContext());
      template = template.copy();
      template.setString(s);
      removeVariablesWhichHasNoSegment(template);
    }

    return expandTemplate(template, null, surroundedText);
  }

  private static TemplateImpl invokeXmlTemplate(XmlTemplateToken token,
                                                CustomTemplateCallback callback,
                                                final int numberInIteration,
                                                @Nullable ZenCodingGenerator generator,
                                                String surroundedText,
                                                final boolean hasChildren) {
    assert generator == null || generator instanceof XmlZenCodingGenerator :
      "The generator cannot process XmlTemplateToken because it doesn't inherit XmlZenCodingGenerator";

    TemplateImpl template = token.getTemplate();
    assert template != null;

    final List<Pair<String, String>> attr2value = new ArrayList<Pair<String, String>>(token.getAttribute2Value());
    final XmlFile xmlFile = token.getFile();
    XmlDocument document = xmlFile.getDocument();
    if (document != null) {
      final XmlTag tag = document.getRootTag();
      if (tag != null) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          public void run() {
            XmlTag tag1 = hasChildren ? expandEmptyTagIfNeccessary(tag) : tag;
            setAttributeValues(tag1, attr2value, numberInIteration);
          }
        });
      }
    }
    String s = (generator != null ? generator : XmlZenCodingGeneratorImpl.INSTANCE).toString(token, hasChildren, callback.getContext());
    template = template.copy();
    template.setString(s);
    removeVariablesWhichHasNoSegment(template);
    Map<String, String> predefinedValues =
      buildPredefinedValues(attr2value, numberInIteration, (XmlZenCodingGenerator)generator, hasChildren);
    return expandTemplate(template, predefinedValues, surroundedText);
  }

  @NotNull
  private static TemplateImpl expandTemplate(@NotNull TemplateImpl template,
                                             Map<String, String> predefinedVarValues,
                                             String surroundedText) {
    LiveTemplateBuilder builder = new LiveTemplateBuilder();
    if (predefinedVarValues == null && surroundedText == null) {
      return template;
    }
    int offset = builder.insertTemplate(0, template, predefinedVarValues);
    if (surroundedText != null) {
      builder.insertText(offset, surroundedText);
    }
    if (offset < builder.length()) {
      builder.insertVariableSegment(offset, TemplateImpl.END);
    }
    return builder.buildTemplate();
  }

  @NotNull
  private static XmlTag expandEmptyTagIfNeccessary(@NotNull XmlTag tag) {
    StringBuilder builder = new StringBuilder();
    boolean flag = false;

    for (PsiElement child : tag.getChildren()) {
      if (child instanceof XmlToken && XmlTokenType.XML_EMPTY_ELEMENT_END.equals(((XmlToken)child).getTokenType())) {
        flag = true;
        break;
      }
      builder.append(child.getText());
    }

    if (flag) {
      builder.append("></").append(tag.getName()).append('>');
      final XmlTag tag1 = XmlElementFactory.getInstance(tag.getProject()).createTagFromText(builder.toString(), XMLLanguage.INSTANCE);
      tag.replace(tag1);
      return tag1;
    }
    return tag;
  }

  private static int gotoChild(Project project, CharSequence text, int offset, int start, int end) {
    PsiFile file = PsiFileFactory.getInstance(project)
      .createFileFromText("dummy.xml", StdFileTypes.XML, text, LocalTimeCounter.currentTime(), false);

    PsiElement element = file.findElementAt(offset);
    if (offset < end && element instanceof XmlToken && ((XmlToken)element).getTokenType() == XmlTokenType.XML_END_TAG_START) {
      return offset;
    }

    int newOffset = -1;
    XmlTag tag = PsiTreeUtil.findElementOfClassAtRange(file, start, end, XmlTag.class);
    if (tag != null) {
      for (PsiElement child : tag.getChildren()) {
        if (child instanceof XmlToken && ((XmlToken)child).getTokenType() == XmlTokenType.XML_END_TAG_START) {
          newOffset = child.getTextOffset();
        }
      }
    }

    if (newOffset >= 0) {
      return newOffset;
    }

    return offset;
  }

  private static void removeVariablesWhichHasNoSegment(TemplateImpl template) {
    Set<String> segments = new HashSet<String>();
    for (int i = 0; i < template.getSegmentsCount(); i++) {
      segments.add(template.getSegmentName(i));
    }
    IntArrayList varsToRemove = new IntArrayList();
    for (int i = 0; i < template.getVariableCount(); i++) {
      String varName = template.getVariableNameAt(i);
      if (!segments.contains(varName)) {
        varsToRemove.add(i);
      }
    }
    for (int i = 0; i < varsToRemove.size(); i++) {
      template.removeVariable(varsToRemove.get(i));
    }
  }

  @Nullable
  private static Map<String, String> buildPredefinedValues(List<Pair<String, String>> attribute2value,
                                                           int numberInIteration,
                                                           XmlZenCodingGenerator generator,
                                                           boolean hasChildren) {
    String attributes = generator.buildAttributesString(attribute2value, hasChildren, numberInIteration);
    attributes = attributes.length() > 0 ? ' ' + attributes : null;
    Map<String, String> predefinedValues = null;
    if (attributes != null) {
      predefinedValues = new HashMap<String, String>();
      predefinedValues.put(ZenCodingTemplate.ATTRS, attributes);
    }
    return predefinedValues;
  }

  private static void setAttributeValues(XmlTag tag, List<Pair<String, String>> attr2value, int numberInIteration) {
    for (Iterator<Pair<String, String>> iterator = attr2value.iterator(); iterator.hasNext();) {
      Pair<String, String> pair = iterator.next();
      if (tag.getAttribute(pair.first) != null) {
        tag.setAttribute(pair.first, ZenCodingUtil.getValue(pair, numberInIteration));
        iterator.remove();
      }
    }
  }

  private static boolean isNewLineBefore(CharSequence text, int offset) {
    int i = offset - 1;
    while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
      if (text.charAt(i) == '\n') {
        return true;
      }
      i--;
    }
    return i < 0;
  }

  private static boolean isNewLineAfter(CharSequence text, int offset) {
    int i = offset;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      if (text.charAt(i) == '\n') {
        return true;
      }
      i++;
    }
    return i == text.length();
  }
}
