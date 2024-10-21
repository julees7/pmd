/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.apex.ast;

import static java.util.stream.Collectors.toList;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import net.sourceforge.pmd.annotation.InternalApi;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextRegion;

import io.github.apexdevtools.apexparser.ApexLexer;
import io.github.apexdevtools.apexparser.CaseInsensitiveInputStream;

@InternalApi
final class ApexCommentBuilder {
    private final TextDocument sourceCode;
    private final CommentInformation commentInfo;

    ApexCommentBuilder(TextDocument sourceCode, String suppressMarker) {
        this.sourceCode = sourceCode;
        this.commentInfo = extractInformationFromComments(sourceCode, suppressMarker);
    }

    public boolean containsComments(ASTCommentContainer commentContainer) {
        if (!commentContainer.hasRealLoc()) {
            // Synthetic nodes don't have a location and can't have comments
            return false;
        }

        TextRegion nodeRegion = commentContainer.getTextRegion();

        // find the first comment after the start of the container node
        int index = Collections.binarySearch(commentInfo.allCommentsByStartIndex, nodeRegion.getStartOffset());

        // no exact hit found - this is expected: there is no comment token starting at
        // the very same index as the node
        assert index < 0 : "comment token is at the same position as non-comment token";
        // extract "insertion point"
        index = ~index;

        // now check whether the next comment after the node is still inside the node
        if (index >= 0 && index < commentInfo.allCommentsByStartIndex.size()) {
            int commentStartIndex = commentInfo.allCommentsByStartIndex.get(index);
            return nodeRegion.getStartOffset() < commentStartIndex
                    && nodeRegion.getEndOffset() >= commentStartIndex;
        }
        return false;
    }

    public void addFormalComments() {
        for (ApexDocToken docToken : commentInfo.docTokens) {
            AbstractApexNode parent = docToken.nearestNode;
            if (parent != null) {
                ASTFormalComment comment = new ASTFormalComment(docToken.token);
                comment.calculateTextRegion(sourceCode);
                parent.insertChild(comment, 0);
            }
        }
    }

    /**
     * Only remembers the node, to which the comment could belong.
     * Since the visiting order of the nodes does not match the source order,
     * the nodes appearing later in the source might be visiting first.
     * The correct node will then be visited afterwards, and since the distance
     * to the comment is smaller, it overrides the remembered node.
     *
     * @param node the potential parent node, to which the comment could belong
     */
    public void buildFormalComment(AbstractApexNode node) {
        if (!node.hasRealLoc()) {
            // Synthetic nodes such as "invoke" ASTMethod for trigger bodies don't have a location in the
            // source code, since they are generated by the parser/compiler (see ApexTreeBuilder)
            return;
        }
        // find the token, that appears as close as possible before the node
        TextRegion nodeRegion = node.getTextRegion();
        for (ApexDocToken docToken : commentInfo.docTokens) {
            if (docToken.token.getStartIndex() > nodeRegion.getStartOffset()) {
                // this and all remaining tokens are after the node
                // so no need to check the remaining tokens.
                break;
            }

            if (docToken.nearestNode == null
                || nodeRegion.compareTo(docToken.nearestNode.getTextRegion()) < 0) {

                docToken.nearestNode = node;
            }
        }
    }

    private static CommentInformation extractInformationFromComments(TextDocument sourceCode, String suppressMarker) {
        String source = sourceCode.getText().toString();
        ApexLexer lexer = new ApexLexer(new CaseInsensitiveInputStream(CharStreams.fromString(source)));

        List<Token> allCommentTokens = new ArrayList<>();
        Map<Integer, String> suppressMap = new HashMap<>();
        int lastStartIndex = -1;
        Token token = lexer.nextToken();

        boolean checkForCommentSuppression = suppressMarker != null;

        while (token.getType() != Token.EOF) {
            // Keep track of all comment tokens
            if (token.getChannel() == ApexLexer.COMMENT_CHANNEL) {
                assert lastStartIndex < token.getStartIndex()
                    : "Comments should be sorted";
                allCommentTokens.add(token);
            }

            if (checkForCommentSuppression && token.getType() == ApexLexer.LINE_COMMENT) {
                // check if it starts with the suppress marker
                String trimmedCommentText = token.getText().substring(2).trim();

                if (trimmedCommentText.startsWith(suppressMarker)) {
                    String userMessage = trimmedCommentText.substring(suppressMarker.length()).trim();
                    suppressMap.put(token.getLine(), userMessage);
                }
            }

            lastStartIndex = token.getStartIndex();
            token = lexer.nextToken();
        }

        return new CommentInformation(suppressMap, allCommentTokens);
    }

    private static class CommentInformation {

        final Map<Integer, String> suppressMap;
        final List<Integer> allCommentsByStartIndex;
        final List<ApexDocToken> docTokens;

        CommentInformation(Map<Integer, String> suppressMap, List<Token> allCommentTokens) {
            this.suppressMap = suppressMap;
            this.docTokens = allCommentTokens.stream()
                .filter(token -> token.getType() == ApexLexer.DOC_COMMENT)
                .map(ApexDocToken::new)
                .collect(toList());
            this.allCommentsByStartIndex = new TokenListByStartIndex(new ArrayList<>(allCommentTokens));
        }
    }

    /**
     * List that maps comment tokens to their start index without copy.
     * This is used to implement a "binary search by key" routine which unfortunately isn't in the stdlib.
     *
     * <p>
     * Note that the provided token list must implement {@link RandomAccess}.
     */
    private static final class TokenListByStartIndex extends AbstractList<Integer> implements RandomAccess {

        private final List<Token> tokens;

        <T extends List<Token> & RandomAccess> TokenListByStartIndex(T tokens) {
            this.tokens = tokens;
        }

        @Override
        public Integer get(int index) {
            return tokens.get(index).getStartIndex();
        }

        @Override
        public int size() {
            return tokens.size();
        }
    }

    private static class ApexDocToken {
        AbstractApexNode nearestNode;
        Token token;

        ApexDocToken(Token token) {
            this.token = token;
        }
    }

    public Map<Integer, String> getSuppressMap() {
        return commentInfo.suppressMap;
    }
}
