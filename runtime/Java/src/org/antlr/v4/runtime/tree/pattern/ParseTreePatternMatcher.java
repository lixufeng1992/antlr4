/*
 * [The "BSD license"]
 *  Copyright (c) 2012 Terence Parr
 *  Copyright (c) 2012 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.antlr.v4.runtime.tree.pattern;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ListTokenSource;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializationOptions;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.misc.Utils;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParseTreePatternMatcher {
	public static class CannotCreateLexerOrParser extends RuntimeException {
		public CannotCreateLexerOrParser(Throwable e) {
			super(e);
		}
	}
	public static class CannotInvokeStartRule extends RuntimeException {
		public CannotInvokeStartRule(Throwable e) {
			super(e);
		}
	}

	protected Class<? extends Lexer> lexerClass;
	protected Class<? extends Parser> parserClass;

	protected Lexer lexer;
	protected Parser parser;

	protected String start = "<", stop=">";
	protected String escape = "\\"; // e.g., \< and \> must escape BOTH!

	/** This ATN has alternatives to match special imaginary tokens for rules like <expr> */
	protected ATN atnWithBypassAlts;

	protected Map<String, Integer> ruleToIndex;

	public ParseTreePatternMatcher() { }

	public ParseTreePatternMatcher(Lexer lexer, Parser parser) {
		this.lexer = lexer;
		this.parser = parser;
		String sATN = parser.getSerializedATN();
		ATNDeserializationOptions deserializationOptions = new ATNDeserializationOptions();
		deserializationOptions.setGenerateRuleBypassTransitions(true);
		atnWithBypassAlts = new ATNDeserializer(deserializationOptions).deserialize(sATN.toCharArray());
		ruleToIndex = Utils.toMap(parser.getRuleNames());
	}

	public void setDelimiters(String start, String stop, String escapeLeft) {
		this.start = start;
		this.stop = stop;
		this.escape = escapeLeft;
	}

	public boolean matches(ParseTree tree, String patternRuleName, String pattern) {
		ParseTreePattern p = compile(patternRuleName, pattern);
		ParseTreeMatch m = matches_(tree, p.patternTree, p);
		return !(m instanceof ParseTreeMatchFailed);
	}

	public boolean matches(ParseTree tree, ParseTreePattern pattern) {
		ParseTreeMatch m = matches_(tree, pattern.patternTree, pattern);
		return !(m instanceof ParseTreeMatchFailed);
	}

	public ParseTreeMatch match(ParseTree tree, String patternRuleName, String pattern) {
		ParseTreePattern p = compile(patternRuleName, pattern);
		return matches_(tree, p.patternTree, p);
	}

	public ParseTreeMatch match(ParseTree tree, ParseTreePattern pattern) {
		return matches_(tree, pattern.patternTree, pattern);
	}

	protected ParseTreeMatch matches_(ParseTree tree, ParseTree patternTree, ParseTreePattern pattern) {
		if ( tree==null || patternTree==null ) {
			return new ParseTreeMatchFailed(tree, null, pattern);
		}
		// x and <ID>, x and y, or x and x; or could be mismatched types
		if ( tree instanceof TerminalNode && patternTree instanceof TerminalNode ) {
			TerminalNode t1 = (TerminalNode)tree;
			TerminalNode t2 = (TerminalNode)patternTree;
			ParseTreeMatch m = null;
			// both are tokens and they have same type
			if ( t1.getSymbol().getType() == t2.getSymbol().getType() ) {
				if ( t2.getSymbol() instanceof TokenTagToken ) { // x and <ID>
					m = new ParseTreeMatch(tree, pattern);
				}
				else if ( t1.getText().equals(t2.getText()) ) { // x and x
					m = new ParseTreeMatch(tree, pattern);
				}
				else { // x and y
					m = new ParseTreeMatchFailed(tree, t1, pattern);
				}
			}
			else {
				m = new ParseTreeMatchFailed(tree, t1, pattern);
			}
			return m;
		}
		if ( tree instanceof ParserRuleContext && patternTree instanceof ParserRuleContext ) {
			ParserRuleContext r1 = (ParserRuleContext)tree;
			ParserRuleContext r2 = (ParserRuleContext)patternTree;
			// (expr ...) and <expr>
			if ( isRuleTag(r2) ) {
				ParseTreeMatch m = null;
				if ( r1.getRuleContext().getRuleIndex() == r2.getRuleContext().getRuleIndex() ) {
					m = new ParseTreeMatch(tree, pattern);
				}
				else {
					m = new ParseTreeMatchFailed(tree, r1, pattern);
				}
				return m;
			}
			// (expr ...) and (expr ...)
			if ( r1.getChildCount()!=r2.getChildCount() ) {
				return new ParseTreeMatchFailed(tree, r1, pattern);
			}
			int n = r1.getChildCount();
			for (int i = 0; i<n; i++) {
				ParseTreeMatch childMatch =
					matches_(r1.getChild(i), patternTree.getChild(i), pattern);
				if ( childMatch instanceof ParseTreeMatchFailed ) {
					return childMatch;
				}
			}
			return new ParseTreeMatch(tree, pattern);
		}
		// if nodes aren't both tokens or both rule nodes, can't match
		return new ParseTreeMatchFailed(tree, tree, pattern);
	}

	/** Is t (expr <expr>) subtree? */
	public boolean isRuleTag(ParseTree t) {
		if ( t instanceof RuleNode ) {
			RuleNode r = (RuleNode)t;
			if ( r.getChildCount()==1 && r.getChild(0) instanceof TerminalNode ) {
				TerminalNode c = (TerminalNode)r.getChild(0);
				if ( c.getSymbol() instanceof RuleTagToken ) {
					System.out.println("rule tag subtree "+t.toStringTree(parser));
					return true;
				}
			}
		}
		return false;
	}

	public ParseTreePattern compile(String patternRuleName, String pattern) {
		List<? extends Token> tokenList = tokenize(pattern);
		ListTokenSource tokenSrc = new ListTokenSource(tokenList);
		CommonTokenStream tokens = new CommonTokenStream(tokenSrc);

		ParserInterpreter parserInterp = new ParserInterpreter(parser.getGrammarFileName(),
															   Arrays.asList(parser.getTokenNames()),
															   Arrays.asList(parser.getRuleNames()),
															   atnWithBypassAlts,
															   tokens);

		ParseTree tree = null;
		try {
			Integer ruleIndex = ruleToIndex.get(patternRuleName);
			tree = parserInterp.parse(ruleIndex);
			System.out.println("pattern tree = "+tree.toStringTree(parserInterp));
		}
		catch (Exception e) {
			throw new CannotInvokeStartRule(e);
		}

		return new ParseTreePattern(patternRuleName, pattern, tree);
	}

	public List<? extends Token> tokenize(String pattern) {
		// make maps for quick look up
		Map<String, Integer> tokenNameToType = toMap(parser.getTokenNames(), 0);
		Map<String, Integer> ruleNameToIndex = toMap(parser.getRuleNames(), 0);

		// split pattern into chunks: sea (raw input) and islands (<ID>, <expr>)
		List<Chunk> chunks = split(pattern);

		// create token stream from text and tags
		List<Token> tokens = new ArrayList<Token>();
		for (Chunk chunk : chunks) {
			if ( chunk instanceof TagChunk ) {
				TagChunk tagChunk = (TagChunk)chunk;
				// add special rule token or conjure up new token from name
				if ( Character.isUpperCase(tagChunk.tag.charAt(0)) ) {
					tokens.add(new TokenTagToken(tagChunk.tag, tokenNameToType.get(tagChunk.tag)));
				}
				else if ( Character.isLowerCase(tagChunk.tag.charAt(0)) ) {
					int ruleIndex = ruleNameToIndex.get(tagChunk.tag);
					int ruleImaginaryTokenType = atnWithBypassAlts.ruleToTokenType[ruleIndex];
					tokens.add(new RuleTagToken(tagChunk.tag, ruleImaginaryTokenType));
				}
				else {
					System.err.println("invalid tag: "+tagChunk.tag);
				}
			}
			else {
				TextChunk textChunk = (TextChunk)chunk;
				try {
					ANTLRInputStream in = new ANTLRInputStream(new StringReader(textChunk.text));
					lexer.setInputStream(in);
					Token t = lexer.nextToken();
					while ( t.getType()!=Token.EOF ) {
						tokens.add(t);
						t = lexer.nextToken();
					}
				}
				catch (IOException ioe) {
					// -----------------
					System.err.println("what?-----------------");
				}
			}
		}

		System.out.println("tokens="+tokens);
		return tokens;
	}

	public static Map<String, Integer> toMap(String[] keys, int offset) {
		Map<String, Integer> m = new HashMap<String, Integer>();
		for (int i=0; i<keys.length; i++) {
			m.put(keys[i], i+offset);
		}
		return m;
	}

	/** Split "<ID> = <e:expr> ;" into 4 chunks for tokenizing by tokenize() */
	public List<Chunk> split(String pattern) {
		int p = 0;
		int n = pattern.length();
		List<Chunk> chunks = new ArrayList<Chunk>();
		StringBuffer buf = new StringBuffer();
		// find all start and stop indexes first, then collect
		List<Integer> starts = new ArrayList<Integer>();
		List<Integer> stops = new ArrayList<Integer>();
		while ( p<n ) {
			if ( p == pattern.indexOf(escape+start,p) ) {
				p += escape.length() + start.length();
			}
			else if ( p == pattern.indexOf(escape+stop,p) ) {
				p += escape.length() + stop.length();
			}
			else if ( p == pattern.indexOf(start,p) ) {
				starts.add(p);
				p += start.length();
			}
			else if ( p == pattern.indexOf(stop,p) ) {
				stops.add(p);
				p += stop.length();
			}
			else {
				p++;
			}
		}

//		System.out.println("");
//		System.out.println(starts);
//		System.out.println(stops);
		if ( starts.size() > stops.size() ) {
			throw new IllegalArgumentException("unterminated tag in pattern: "+pattern);
		}

		if ( starts.size() < stops.size() ) {
			throw new IllegalArgumentException("missing start tag in pattern: "+pattern);
		}

		int ntags = starts.size();
		for (int i=0; i<ntags; i++) {
			if ( starts.get(i)>=stops.get(i) ) {
				throw new IllegalArgumentException("tag delimiters out of order in pattern: "+pattern);
			}
		}

		// collect into chunks now
		if ( ntags==0 ) {
			String text = pattern.substring(0, n);
			chunks.add(new TextChunk(text));
		}

		if ( ntags>0 && starts.get(0)>0 ) { // copy text up to first tag into chunks
			String text = pattern.substring(0, starts.get(0));
			chunks.add(new TextChunk(text));
		}
		for (int i=0; i<ntags; i++) {
			// copy inside of <tag>
			String tag = pattern.substring(starts.get(i) + start.length(), stops.get(i));
			chunks.add(new TagChunk(tag));
			if ( i+1 < ntags ) {
				// copy from end of <tag> to start of next
				String text = pattern.substring(stops.get(i) + stop.length(), starts.get(i + 1));
				chunks.add(new TextChunk(text));
			}
		}
		if ( ntags>0 ) {
			int afterLastTag = stops.get(ntags - 1) + stop.length();
			if ( afterLastTag < n ) { // copy text from end of last tag to end
				String text = pattern.substring(afterLastTag, n);
				chunks.add(new TextChunk(text));
			}
		}

		// strip out the escape sequences from text chunks but not tags
		for (Chunk c : chunks) {
			if ( c instanceof TextChunk ) {
				TextChunk tc = (TextChunk)c;
				tc.text = tc.text.replace(escape, "");
			}
		}

		return chunks;
	}

	public MatchIterator findAll(ParseTree t, int ruleIndex, String pattern) {
		ParseTreeWalker walker = new ParseTreeWalker();
		return null;
	}

	// preorder
	protected List<ParseTree> findAll(ParseTree t, ParseTreePattern pattern) {
		List<ParseTree> subtrees = new ArrayList<ParseTree>();
		findAll_(t, pattern, subtrees);
		return subtrees;
	}

	protected void findAll_(ParseTree t, ParseTreePattern pattern, List<ParseTree> subtrees) {
		if ( pattern.matches(t) ) {
			subtrees.add(t);
		}
		if ( t instanceof RuleNode) {
			RuleNode r = (RuleNode)t;
			int n = r.getChildCount();
			for (int i = 0; i<n; i++) {
				findAll(r.getChild(i), pattern);
			}
		}
	}

	public Lexer getLexer() {
		return lexer;
	}

	public Parser getParser() {
		return parser;
	}
}
