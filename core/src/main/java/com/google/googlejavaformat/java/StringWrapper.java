/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.googlejavaformat.java;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeMap;
import com.google.googlejavaformat.Newlines;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.JavacParser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;
import com.sun.tools.javac.util.Position;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;

/** Wraps string literals that exceed the column limit. */
public final class StringWrapper {

  public static final String TEXT_BLOCK_DELIMITER = "\"\"\"";

  /** Reflows long string literals in the given Java source code. */
  public static String wrap(String input, Formatter formatter) throws FormatterException {
    return StringWrapper.wrap(Formatter.MAX_LINE_LENGTH, input, formatter);
  }

  /**
   * Reflows string literals in the given Java source code that extend past the given column limit.
   */
  static String wrap(final int columnLimit, String input, Formatter formatter)
      throws FormatterException {
    if (!needWrapping(columnLimit, input)) {
      // fast path
      return input;
    }

    TreeRangeMap<Integer, String> replacements = getReflowReplacements(columnLimit, input);
    String firstPass = formatter.formatSource(input, replacements.asMapOfRanges().keySet());

    if (!firstPass.equals(input)) {
      // If formatting the replacement ranges resulted in a change, recalculate the replacements on
      // the updated input.
      input = firstPass;
      replacements = getReflowReplacements(columnLimit, input);
    }

    String result = applyReplacements(input, replacements);

    {
      // We really don't want bugs in this pass to change the behaviour of programs we're
      // formatting, so check that the pretty-printed AST is the same before and after reformatting.
      String expected = parse(input, /* allowStringFolding= */ true).toString();
      String actual = parse(result, /* allowStringFolding= */ true).toString();
      if (!expected.equals(actual)) {
        throw new FormatterException(
            String.format(
                "Something has gone terribly wrong. We planned to make the below formatting change,"
                    + " but have aborted because it would unexpectedly change the AST.\n"
                    + "Please file a bug: "
                    + "https://github.com/google/google-java-format/issues/new"
                    + "\n\n=== Actual: ===\n%s\n=== Expected: ===\n%s\n",
                actual, expected));
      }
    }

    return result;
  }

  private static TreeRangeMap<Integer, String> getReflowReplacements(
      int columnLimit, final String input) throws FormatterException {
    return new Reflower(columnLimit, input).getReflowReplacements();
  }

  private static class Reflower {

    private final String input;
    private final int columnLimit;
    private final String separator;
    private final JCTree.JCCompilationUnit unit;
    private final Position.LineMap lineMap;

    Reflower(int columnLimit, String input) throws FormatterException {
      this.columnLimit = columnLimit;
      this.input = input;
      this.separator = Newlines.guessLineSeparator(input);
      this.unit = parse(input, /* allowStringFolding= */ false);
      this.lineMap = unit.getLineMap();
    }

    TreeRangeMap<Integer, String> getReflowReplacements() {
      // Paths to string literals that extend past the column limit.
      List<TreePath> longStringLiterals = new ArrayList<>();
      // Paths to text blocks to be re-indented.
      List<Tree> textBlocks = new ArrayList<>();
      new LongStringsAndTextBlockScanner(longStringLiterals, textBlocks)
          .scan(new TreePath(unit), null);
      TreeRangeMap<Integer, String> replacements = TreeRangeMap.create();
      indentTextBlocks(replacements, textBlocks);
      wrapLongStrings(replacements, longStringLiterals);
      return replacements;
    }

    private class LongStringsAndTextBlockScanner extends TreePathScanner<Void, Void> {

      private final List<TreePath> longStringLiterals;
      private final List<Tree> textBlocks;

      LongStringsAndTextBlockScanner(List<TreePath> longStringLiterals, List<Tree> textBlocks) {
        this.longStringLiterals = longStringLiterals;
        this.textBlocks = textBlocks;
      }

      @Override
      public Void visitLiteral(LiteralTree literalTree, Void aVoid) {
        if (literalTree.getKind() != Kind.STRING_LITERAL) {
          return null;
        }
        int pos = getStartPosition(literalTree);
        if (input.substring(pos, min(input.length(), pos + 3)).equals(TEXT_BLOCK_DELIMITER)) {
          textBlocks.add(literalTree);
          return null;
        }
        Tree parent = getCurrentPath().getParentPath().getLeaf();
        if (parent instanceof MemberSelectTree
            && ((MemberSelectTree) parent).getExpression().equals(literalTree)) {
          return null;
        }
        int endPosition = getEndPosition(unit, literalTree);
        int lineEnd = endPosition;
        while (Newlines.hasNewlineAt(input, lineEnd) == -1) {
          lineEnd++;
        }
        if (lineMap.getColumnNumber(lineEnd) - 1 <= columnLimit) {
          return null;
        }
        longStringLiterals.add(getCurrentPath());
        return null;
      }
    }

    private void indentTextBlocks(
        TreeRangeMap<Integer, String> replacements, List<Tree> textBlocks) {
      for (Tree tree : textBlocks) {
        int startPosition = lineMap.getStartPosition(lineMap.getLineNumber(getStartPosition(tree)));
        int endPosition = getEndPosition(unit, tree);
        String text = input.substring(startPosition, endPosition);
        int leadingWhitespace = CharMatcher.whitespace().negate().indexIn(text);

        // Find the source code of the text block with incidental whitespace removed.
        // The first line of the text block is always """, and it does not affect incidental
        // whitespace.
        ImmutableList<String> initialLines = text.lines().collect(toImmutableList());
        String stripped = initialLines.stream().skip(1).collect(joining(separator)).stripIndent();
        ImmutableList<String> lines = stripped.lines().collect(toImmutableList());
        boolean deindent =
            getLast(initialLines).stripTrailing().length()
                == getLast(lines).stripTrailing().length();

        String prefix = deindent ? "" : " ".repeat(leadingWhitespace);

        StringBuilder output = new StringBuilder(prefix).append(initialLines.get(0).stripLeading());
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          String trimmed = line.stripTrailing();
          output.append(separator);
          if (!trimmed.isEmpty()) {
            // Don't add incidental leading whitespace to empty lines
            output.append(prefix);
          }
          if (i == lines.size() - 1) {
            String withoutDelimiter =
                trimmed
                    .substring(0, trimmed.length() - TEXT_BLOCK_DELIMITER.length())
                    .stripTrailing();
            if (!withoutDelimiter.stripLeading().isEmpty()) {
              output.append(withoutDelimiter).append('\\').append(separator).append(prefix);
            }
            // If the trailing line is just """, indenting it more than the prefix of incidental
            // whitespace has no effect, and results in a javac text-blocks warning that 'trailing
            // white space will be removed'.
            output.append(TEXT_BLOCK_DELIMITER);
          } else {
            output.append(line);
          }
        }
        replacements.put(Range.closedOpen(startPosition, endPosition), output.toString());
      }
    }

    private void wrapLongStrings(
        TreeRangeMap<Integer, String> replacements, List<TreePath> longStringLiterals) {
      for (TreePath path : longStringLiterals) {
        // Find the outermost contiguous enclosing concatenation expression
        TreePath enclosing = path;
        while (enclosing.getParentPath().getLeaf().getKind() == Kind.PLUS) {
          enclosing = enclosing.getParentPath();
        }
        // Is the literal being wrapped the first in a chain of concatenation expressions?
        // i.e. `ONE + TWO + THREE`
        // We need this information to handle continuation indents.
        AtomicBoolean first = new AtomicBoolean(false);
        // Finds the set of string literals in the concat expression that includes the one that
        // needs
        // to be wrapped.
        List<Tree> flat = flatten(input, unit, path, enclosing, first);
        // Zero-indexed start column
        int startColumn = lineMap.getColumnNumber(getStartPosition(flat.get(0))) - 1;

        // Handling leaving trailing non-string tokens at the end of the literal,
        // e.g. the trailing `);` in `foo("...");`.
        int end = getEndPosition(unit, getLast(flat));
        int lineEnd = end;
        while (Newlines.hasNewlineAt(input, lineEnd) == -1) {
          lineEnd++;
        }
        int trailing = lineEnd - end;

        // Get the original source text of the string literals, excluding `"` and `+`.
        ImmutableList<String> components = stringComponents(input, unit, flat);
        replacements.put(
            Range.closedOpen(getStartPosition(flat.get(0)), getEndPosition(unit, getLast(flat))),
            reflow(separator, columnLimit, startColumn, trailing, components, first.get()));
      }
    }
  }

  /**
   * Returns the source text of the given string literal trees, excluding the leading and trailing
   * double-quotes and the `+` operator.
   */
  private static ImmutableList<String> stringComponents(
      String input, JCTree.JCCompilationUnit unit, List<Tree> flat) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    StringBuilder piece = new StringBuilder();
    for (Tree tree : flat) {
      // adjust for leading and trailing double quotes
      String text = input.substring(getStartPosition(tree) + 1, getEndPosition(unit, tree) - 1);
      int start = 0;
      for (int idx = 0; idx < text.length(); idx++) {
        if (CharMatcher.whitespace().matches(text.charAt(idx))) {
          // continue below
        } else if (hasEscapedWhitespaceAt(text, idx) != -1) {
          // continue below
        } else if (hasEscapedNewlineAt(text, idx) != -1) {
          int length;
          while ((length = hasEscapedNewlineAt(text, idx)) != -1) {
            idx += length;
          }
        } else {
          continue;
        }
        piece.append(text, start, idx);
        result.add(piece.toString());
        piece = new StringBuilder();
        start = idx;
      }
      if (piece.length() > 0) {
        result.add(piece.toString());
        piece = new StringBuilder();
      }
      if (start < text.length()) {
        piece.append(text, start, text.length());
      }
    }
    if (piece.length() > 0) {
      result.add(piece.toString());
    }
    return result.build();
  }

  static int hasEscapedWhitespaceAt(String input, int idx) {
    if (input.startsWith("\\t", idx)) {
      return 2;
    }
    return -1;
  }

  static int hasEscapedNewlineAt(String input, int idx) {
    int offset = 0;
    if (input.startsWith("\\r", idx)) {
      offset += 2;
    }
    if (input.startsWith("\\n", idx)) {
      offset += 2;
    }
    return offset > 0 ? offset : -1;
  }

  /**
   * Reflows the given source text, trying to split on word boundaries.
   *
   * @param separator the line separator
   * @param columnLimit the number of columns to wrap at
   * @param startColumn the column position of the beginning of the original text
   * @param trailing extra space to leave after the last line, to accommodate a ; or )
   * @param components the text to reflow. This is a list of “words” of a single literal. Its first
   *     and last quotes have been stripped
   * @param first0 true if the text includes the beginning of its enclosing concat chain
   */
  private static String reflow(
      String separator,
      int columnLimit,
      int startColumn,
      int trailing,
      ImmutableList<String> components,
      boolean first0) {
    // We have space between the start column and the limit to output the first line.
    // Reserve two spaces for the start and end quotes.
    int width = columnLimit - startColumn - 2;
    Deque<String> input = new ArrayDeque<>(components);
    List<String> lines = new ArrayList<>();
    boolean first = first0;
    while (!input.isEmpty()) {
      int length = 0;
      List<String> line = new ArrayList<>();
      // If we know this is going to be the last line, then remove a bit of width to account for the
      // trailing characters.
      if (totalLengthLessThanOrEqual(input, width)) {
        // This isn’t quite optimal, but arguably good enough. See b/179561701
        width -= trailing;
      }
      while (!input.isEmpty() && (length <= 4 || (length + input.peekFirst().length()) <= width)) {
        String text = input.removeFirst();
        line.add(text);
        length += text.length();
        if (text.endsWith("\\n") || text.endsWith("\\r")) {
          break;
        }
      }
      if (line.isEmpty()) {
        line.add(input.removeFirst());
      }
      // add the split line to the output, and process whatever's left
      lines.add(String.join("", line));
      if (first) {
        width -= 6; // subsequent lines have a four-space continuation indent and a `+ `
        first = false;
      }
    }

    return lines.stream()
        .collect(
            joining(
                "\"" + separator + Strings.repeat(" ", startColumn + (first0 ? 4 : -2)) + "+ \"",
                "\"",
                "\""));
  }

  private static boolean totalLengthLessThanOrEqual(Iterable<String> input, int length) {
    int total = 0;
    for (String s : input) {
      total += s.length();
      if (total > length) {
        return false;
      }
    }
    return true;
  }

  /**
   * Flattens the given binary expression tree, and extracts the subset that contains the given path
   * and any adjacent nodes that are also string literals.
   */
  private static List<Tree> flatten(
      String input,
      JCTree.JCCompilationUnit unit,
      TreePath path,
      TreePath parent,
      AtomicBoolean firstInChain) {
    List<Tree> flat = new ArrayList<>();

    // flatten the expression tree with a pre-order traversal
    ArrayDeque<Tree> todo = new ArrayDeque<>();
    todo.add(parent.getLeaf());
    while (!todo.isEmpty()) {
      Tree first = todo.removeFirst();
      if (first.getKind() == Tree.Kind.PLUS) {
        BinaryTree bt = (BinaryTree) first;
        todo.addFirst(bt.getRightOperand());
        todo.addFirst(bt.getLeftOperand());
      } else {
        flat.add(first);
      }
    }

    int idx = flat.indexOf(path.getLeaf());
    Verify.verify(idx != -1);

    // walk outwards from the leaf for adjacent string literals to also reflow
    int startIdx = idx;
    int endIdx = idx + 1;
    while (startIdx > 0
        && flat.get(startIdx - 1).getKind() == Tree.Kind.STRING_LITERAL
        && noComments(input, unit, flat.get(startIdx - 1), flat.get(startIdx))) {
      startIdx--;
    }
    while (endIdx < flat.size()
        && flat.get(endIdx).getKind() == Tree.Kind.STRING_LITERAL
        && noComments(input, unit, flat.get(endIdx - 1), flat.get(endIdx))) {
      endIdx++;
    }

    firstInChain.set(startIdx == 0);
    return ImmutableList.copyOf(flat.subList(startIdx, endIdx));
  }

  private static boolean noComments(
      String input, JCTree.JCCompilationUnit unit, Tree one, Tree two) {
    return STRING_CONCAT_DELIMITER.matchesAllOf(
        input.subSequence(getEndPosition(unit, one), getStartPosition(two)));
  }

  public static final CharMatcher STRING_CONCAT_DELIMITER =
      CharMatcher.whitespace().or(CharMatcher.anyOf("\"+"));

  private static int getEndPosition(JCTree.JCCompilationUnit unit, Tree tree) {
    return ((JCTree) tree).getEndPosition(unit.endPositions);
  }

  private static int getStartPosition(Tree tree) {
    return ((JCTree) tree).getStartPosition();
  }

  /**
   * Returns true if any lines in the given Java source exceed the column limit, or contain a {@code
   * """} that could indicate a text block.
   */
  private static boolean needWrapping(int columnLimit, String input) {
    // TODO(cushon): consider adding Newlines.lineIterable?
    Iterator<String> it = Newlines.lineIterator(input);
    while (it.hasNext()) {
      String line = it.next();
      if (line.length() > columnLimit || line.contains(TEXT_BLOCK_DELIMITER)) {
        return true;
      }
    }
    return false;
  }

  /** Parses the given Java source. */
  private static JCTree.JCCompilationUnit parse(String source, boolean allowStringFolding)
      throws FormatterException {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Context context = new Context();
    context.put(DiagnosticListener.class, diagnostics);
    Options.instance(context).put("--enable-preview", "true");
    Options.instance(context).put("allowStringFolding", Boolean.toString(allowStringFolding));
    JavacFileManager fileManager = new JavacFileManager(context, true, UTF_8);
    try {
      fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, ImmutableList.of());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    SimpleJavaFileObject sjfo =
        new SimpleJavaFileObject(URI.create("source"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    Log.instance(context).useSource(sjfo);
    ParserFactory parserFactory = ParserFactory.instance(context);
    JavacParser parser =
        parserFactory.newParser(
            source, /* keepDocComments= */ true, /* keepEndPos= */ true, /* keepLineMap= */ true);
    JCTree.JCCompilationUnit unit = parser.parseCompilationUnit();
    unit.sourcefile = sjfo;
    Iterable<Diagnostic<? extends JavaFileObject>> errorDiagnostics =
        Iterables.filter(diagnostics.getDiagnostics(), Formatter::errorDiagnostic);
    if (!Iterables.isEmpty(errorDiagnostics)) {
      // error handling is done during formatting
      throw FormatterException.fromJavacDiagnostics(errorDiagnostics);
    }
    return unit;
  }

  /** Applies replacements to the given string. */
  private static String applyReplacements(
      String javaInput, TreeRangeMap<Integer, String> replacementMap) throws FormatterException {
    // process in descending order so the replacement ranges aren't perturbed if any replacements
    // differ in size from the input
    Map<Range<Integer>, String> ranges = replacementMap.asDescendingMapOfRanges();
    if (ranges.isEmpty()) {
      return javaInput;
    }
    StringBuilder sb = new StringBuilder(javaInput);
    for (Map.Entry<Range<Integer>, String> entry : ranges.entrySet()) {
      Range<Integer> range = entry.getKey();
      sb.replace(range.lowerEndpoint(), range.upperEndpoint(), entry.getValue());
    }
    return sb.toString();
  }

  private StringWrapper() {}
}
