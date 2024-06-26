// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.source.codeStyle;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.FormattingDocumentModelImpl;
import com.intellij.psi.xml.*;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class ImportsFormatter extends XmlRecursiveElementVisitor {
  private static final Logger LOG = Logger.getInstance(ImportsFormatter.class);
  
  private final FormattingDocumentModelImpl myDocumentModel;
  private final CommonCodeStyleSettings.IndentOptions myIndentOptions;
  private static final @NonNls String PAGE_DIRECTIVE = "page";
  private static final @NonNls String IMPORT_ATT = "import";

  private final PostFormatProcessorHelper myPostProcessor;

  public ImportsFormatter(@NotNull CodeStyleSettings settings, @NotNull PsiFile file) {
    myPostProcessor = new PostFormatProcessorHelper(settings.getCommonSettings(file.getLanguage()));
    myDocumentModel = FormattingDocumentModelImpl.createOn(file);
    myIndentOptions = settings.getIndentOptionsByFile(file);
  }

  @Override public void visitXmlTag(@NotNull XmlTag tag) {
    if (checkElementContainsRange(tag)) {
      super.visitXmlTag(tag);
    }
  }

  private static boolean isPageDirectiveTag(final XmlTag tag) {
    return PAGE_DIRECTIVE.equals(tag.getName());
  }

  @Override public void visitXmlText(@NotNull XmlText text) {

  }

  @Override public void visitXmlAttribute(@NotNull XmlAttribute attribute) {
    if (isPageDirectiveTag(attribute.getParent())) {
      final XmlAttributeValue valueElement = attribute.getValueElement();
      if (valueElement != null && checkRangeContainsElement(attribute) && isImportAttribute(attribute) && PostFormatProcessorHelper
        .isMultiline(valueElement)) {
        final int oldLength = attribute.getTextLength();
        ASTNode valueToken = findValueToken(valueElement.getNode());
        if (valueToken != null) {
          String newAttributeValue = formatImports(valueToken.getStartOffset(), Objects.requireNonNull(attribute.getValue()));
          try {
            attribute.setValue(newAttributeValue);
          }
          catch (IncorrectOperationException e) {
            LOG.error(e);
          }
          finally {
            updateResultRange(oldLength, attribute.getTextLength());
          }
        }
      }
    }
  }

  private String formatImports(final int startOffset, final String value) {
    final StringBuilder result = new StringBuilder();
    String offset = calcOffset(startOffset);
    final String[] imports = value.split(",");
    if (imports.length >=1) {
      result.append(imports[0]);
      for (int i = 1; i < imports.length; i++) {
        String anImport = imports[i];
        result.append(',');
        result.append('\n');
        result.append(offset);
        result.append(anImport.trim());
      }
    }
    return result.toString();
  }

  private String calcOffset(final int startOffset) {
    final StringBuffer result = new StringBuffer();

    final int lineStartOffset = myDocumentModel.getLineStartOffset(myDocumentModel.getLineNumber(startOffset));
    final int emptyLineEnd = CharArrayUtil.shiftForward(myDocumentModel.getDocument().getCharsSequence(), lineStartOffset, " \t");
    final CharSequence spaces = myDocumentModel.getText(new TextRange(lineStartOffset, emptyLineEnd));

    result.append(spaces);

    appendSpaces(result, startOffset - emptyLineEnd);

    return result.toString();
  }

  private void appendSpaces(final StringBuffer result, final int count) {
    if (myIndentOptions.USE_TAB_CHARACTER && ! myIndentOptions.SMART_TABS) {
      int tabsCount = count / myIndentOptions.TAB_SIZE;
      int spaceCount = count - tabsCount * myIndentOptions.TAB_SIZE;
      StringUtil.repeatSymbol(result, '\t', tabsCount);
      StringUtil.repeatSymbol(result, ' ', spaceCount);
    } else {
      StringUtil.repeatSymbol(result, ' ', count);
    }
  }

  private static ASTNode findValueToken(final ASTNode node) {
    ASTNode child = node.getFirstChildNode();
    while (child != null){
      if (child.getElementType() == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) return child;
      child = child.getTreeNext();
    }
    return null;
  }

  private static boolean isImportAttribute(final XmlAttribute attribute) {
    return IMPORT_ATT.equals(attribute.getName());
  }

  protected void updateResultRange(final int oldTextLength, final int newTextLength) {
    myPostProcessor.updateResultRange(oldTextLength, newTextLength);
  }

  protected boolean checkElementContainsRange(final PsiElement element) {
    return myPostProcessor.isElementPartlyInRange(element);
  }

  protected boolean checkRangeContainsElement(final PsiElement element) {
    return myPostProcessor.isElementFullyInRange(element);
  }

  public PsiElement process(PsiElement formatted) {
    LOG.assertTrue(formatted.isValid());
    formatted.accept(this);
    return formatted;
  }

  public TextRange processText(final PsiFile source, final TextRange rangeToReformat) {
    myPostProcessor.setResultTextRange(rangeToReformat);
    source.accept(this);
    return myPostProcessor.getResultTextRange();
  }
}
