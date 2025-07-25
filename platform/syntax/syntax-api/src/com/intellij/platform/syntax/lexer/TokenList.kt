// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:ApiStatus.Experimental
@file:JvmName("TokenListUtil")

package com.intellij.platform.syntax.lexer

import com.intellij.platform.syntax.CancellationProvider
import com.intellij.platform.syntax.Logger
import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.SyntaxElementTypeSet
import com.intellij.platform.syntax.impl.builder.DIAGNOSTICS
import kotlin.jvm.JvmName
import org.jetbrains.annotations.ApiStatus

/**
 * This interface represents the result of lexing: text and the tokens produced from it by some lexer.
 * It allows clients to inspect all tokens at once and move back and forward to implement some simple lexer-based checks.
 *
 * @see TokenList(IntArray, Array<SyntaxElementType>, int, CharSequence)
 * @see performLexing
 * @see tokenListLexer
 */
@ApiStatus.Experimental
interface TokenList {
  /**
   * @return the number of tokens inside
   */
  val tokenCount: Int

  /**
   * @return the full text that was split into the tokens represented here
   */
  val tokenizedText: CharSequence

  /**
   * @return the start offset of the token with the given index
   */
  fun getTokenStart(index: Int): Int

  /**
   * @return the end offset of the token with the given index
   */
  fun getTokenEnd(index: Int): Int

  /**
   * @return the type of the token with the given index, or null if the index is negative or exceeds token count
   */
  fun getTokenType(index: Int): SyntaxElementType?

  /**
   * @return the text of the token with the given index, or null if the index is negative or exceeds token count
   */
  fun getTokenText(index: Int): CharSequence? {
    if (index < 0 || index >= tokenCount) return null
    return tokenizedText.subSequence(getTokenStart(index), getTokenEnd(index))
  }

  fun slice(start: Int, end: Int): TokenList

  fun remap(index: Int, newValue: SyntaxElementType)
}

/**
 * Performs lexing of the given text with the given lexer.
 *
 * @return a TokenList representing the result of lexing
 */
fun performLexing(
  text: CharSequence,
  lexer: Lexer,
  cancellationProvider: CancellationProvider?,
  logger: Logger?,
): TokenList {
  if (lexer is TokenListLexerImpl) {
    val existing = lexer.tokens
    if (existing is TokenListImpl && equal(text, existing.tokenizedText)) {
      // prevent clients like PsiBuilder from modifying shared token types
      return TokenListImpl(
        lexStarts = existing.lexStarts,
        lexTypes = existing.lexTypes.copyOf(),
        tokenCount = existing.tokenCount,
        tokenizedText = text
      ) as TokenList
    }
  }
  val sequence = Builder(text, lexer, cancellationProvider, logger).performLexing()

  DIAGNOSTICS?.registerPass(text.length, sequence.tokenCount)

  return sequence
}

/**
 * Creates a TokenList from the given arrays.
 *
 * @param lexStarts the start offsets of the tokens, also `lexStarts[tokenCount]` should be the end offset of the last token. Can be longer than `[tokenCount] + 1`.
 * @param lexTypes the types of the tokens. Can be longer than `[tokenCount]`.
 * @param tokenCount the number of tokens.
 * @param tokenizedText the full text that was split into the tokens.
 */
fun TokenList(
  lexStarts: IntArray,
  lexTypes: Array<SyntaxElementType>,
  tokenCount: Int,
  tokenizedText: CharSequence,
): TokenList = TokenListImpl(lexStarts, lexTypes, tokenCount, tokenizedText)

/**
 * Creates an adapter from the given TokenList to [Lexer] interface.
 */
fun tokenListLexer(tokenList: TokenList, logger: Logger? = null): Lexer =
  TokenListLexerImpl(tokenList, logger)

/**
 * @return whether [.getTokenType](index) would return the given type
 */
fun TokenList.hasType(index: Int, type: SyntaxElementType): Boolean =
  getTokenType(index) === type

/**
 * @return whether [.getTokenType](index) would return any of the given types (null acceptable, indicating start or end of the text)
 */
fun TokenList.hasType(index: Int, vararg types: SyntaxElementType?): Boolean =
  getTokenType(index) in types

/**
 * @return whether [.getTokenType](index) would return a type in the given set
 */
fun TokenList.hasType(index: Int, types: SyntaxElementTypeSet): Boolean =
  getTokenType(index) in types

/**
 * Moves back, potentially skipping tokens which represent a valid nesting sequence
 * with the given types for opening and closing braces.
 * @return an index `prev` of a token before `index` such that either:
 *
 *  1. `prev == index - 1`
 *  1. `hasType(prev + 1, opening) && hasType(index, closing)` and every opening brace between those indices has its closing one before `index`
 *
 */
fun TokenList.backWithBraceMatching(index: Int, opening: SyntaxElementType, closing: SyntaxElementType): Int {
  var index = index
  if (getTokenType(index) === closing) {
    var nesting = 1
    while (nesting > 0 && index > 0) {
      index--
      val type = getTokenType(index)
      if (type === closing) {
        nesting++
      }
      else if (type === opening) {
        nesting--
      }
    }
  }
  return index - 1
}

/**
 * Moves back from `index` while tokens belong to the given set
 * @return the largest `prev <= index` whose token type doesn't belong to `toSkip`
 */
fun TokenList.backWhile(index: Int, toSkip: SyntaxElementTypeSet): Int {
  var index = index
  while (hasType(index, toSkip)) {
    index--
  }
  return index
}

/**
 * Moves forward from `index` while tokens belong to the given set
 * @return the smallest `next >= index` whose token type doesn't belong to `toSkip`
 */
fun TokenList.forwardWhile(index: Int, toSkip: SyntaxElementTypeSet): Int {
  var index = index
  while (hasType(index, toSkip)) {
    index++
  }
  return index
}