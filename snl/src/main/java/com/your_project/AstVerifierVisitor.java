package com.your_project;

import org.antlr.v4.runtime.ParserRuleContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AstVerifierVisitor extends snlBaseVisitor<String> { // NOTE: The return type is now String
    private int indentLevel = 0;
    // A stack of scopes. Each scope is a map from identifier name to its type.
    // We also store class/entity definitions to check member access.
    private Map<String, Map<String, String>> typeDefinitions = new HashMap<>();
    private String currentTypeName = null; // To know which class/entity we are currently defining

    private Deque<Map<String, String>> scopes = new ArrayDeque<>();
    public final List<String> semanticErrors = new ArrayList<>();

    private void printIndent(String message) {
        for (int i = 0; i < indentLevel; i++) System.out.print("  ");
        System.out.println(message);
    }

    private void enterScope() {
        scopes.push(new HashMap<>());
    }

    private void exitScope() {
        scopes.pop();
    }

    // Finds a symbol in the current or outer scopes.
    private String findSymbol(String name) {
        for (Map<String, String> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    // Defines a symbol in the current scope.
    private void defineSymbol(String name, String type, ParserRuleContext ctx) {
        Map<String, String> currentScope = scopes.peek();
        if (currentScope != null && currentScope.containsKey(name)) {
            String error = "Semantic Error (line " + ctx.getStart().getLine() + "): Identifier '" + name + "' is already defined in this scope.";
            semanticErrors.add(error);
        } else if (currentScope != null) {
            currentScope.put(name, type);
        }
    }

    private void checkAssignment(String lhsType, String rhsType, ParserRuleContext ctx) {
        if (lhsType == null || rhsType == null || lhsType.equals("error_type") || rhsType.equals("error_type")) {
            return; // Avoid cascade errors if the right-hand side already has an error.
        }
        if (!lhsType.equals(rhsType)) {
            String error = "Semantic Error (line " + ctx.getStart().getLine() + "): Type mismatch. Cannot assign expression of type '" + rhsType + "' to a variable of type '" + lhsType + "'.";
            semanticErrors.add(error);
        }
    }

    @Override
    public String visitProject(snlParser.ProjectContext ctx) {
        enterScope(); // Global scope
        String projectName = ctx.STRING().getText();
        String language = ctx.language().getText();
        printIndent("Vérification du projet: " + projectName + " en langage " + language);
        indentLevel++;
        visitChildren(ctx);
        indentLevel--;
        exitScope();
        return null;
    }

    @Override
    public String visitClassDecl(snlParser.ClassDeclContext ctx) {
        String className = ctx.ID(0).getText();
        String parentInfo = ctx.ID().size() > 1 ? " (hérite de " + ctx.ID(1).getText() + ")" : "";
        currentTypeName = className;
        typeDefinitions.put(className, new HashMap<>());
        defineSymbol(className, "class", ctx);
        printIndent("Détection de la classe: '" + className + "'" + parentInfo);
        enterScope();
        indentLevel++;
        visitChildren(ctx);
        indentLevel--;
        exitScope();
        currentTypeName = null;
        return null;
    }

    @Override
    public String visitFieldDecl(snlParser.FieldDeclContext ctx) {
        String visibility = ctx.visibility() != null ? ctx.visibility().getText() + " " : "";
        String type = ctx.type().getText();
        String name = ctx.ID().getText();
        defineSymbol(name, type, ctx);
        if (currentTypeName != null) {
            typeDefinitions.get(currentTypeName).put(name, type);
        }
        if (ctx.expression() != null) {
            String valueType = visit(ctx.expression());
            checkAssignment(type, valueType, ctx);
        }
        String valueInfo = ctx.expression() != null ? " = " + ctx.expression().getText() : "";
        printIndent("Détection d'un champ: " + visibility + type + " " + name + valueInfo);
        return null;
    }

    @Override
    public String visitEntityDecl(snlParser.EntityDeclContext ctx) {
        String entityName = ctx.ID().getText();
        currentTypeName = entityName;
        typeDefinitions.put(entityName, new HashMap<>());
        defineSymbol(entityName, "entity", ctx);
        printIndent("Détection de l'entité: '" + entityName + "'");
        enterScope();
        indentLevel++;
        visitChildren(ctx);
        indentLevel--;
        exitScope();
        currentTypeName = null;
        return null;
    }

    @Override
    public String visitEntityMember(snlParser.EntityMemberContext ctx) {
        String name = ctx.ID().getText();
        String type = ctx.type().getText();
        defineSymbol(name, type, ctx);
        if (currentTypeName != null) {
            typeDefinitions.get(currentTypeName).put(name, type);
        }
        if (ctx.expression() != null) {
            String valueType = visit(ctx.expression());
            checkAssignment(type, valueType, ctx);
        }
        String valueInfo = ctx.expression() != null ? " = " + ctx.expression().getText() : "";
        printIndent("Membre d'entité: " + name + " : " + type + valueInfo);
        // Ne pas appeler visitChildren ici pour éviter de descendre dans l'expression
        return null;
    }

    @Override
    public String visitMethodDecl(snlParser.MethodDeclContext ctx) {
        String methodName = ctx.ID().getText();
        String visibility = ctx.visibility() != null ? ctx.visibility().getText() + " " : "";
        defineSymbol(methodName, "method", ctx);
        printIndent("Détection de la méthode: " + visibility + "'" + methodName + "'");
        enterScope();
        indentLevel++;
        visitChildren(ctx);
        indentLevel--;
        exitScope();
        return null;
    }

    @Override
    public String visitFunctionDecl(snlParser.FunctionDeclContext ctx) {
        String funcName = ctx.ID().getText();
        defineSymbol(funcName, "function", ctx);
        printIndent("Détection de la fonction: '" + funcName + "'");
        enterScope();
        indentLevel++;
        visitChildren(ctx);
        indentLevel--;
        exitScope();
        return null;
    }

    @Override
    public String visitVarDecl(snlParser.VarDeclContext ctx) {
        String type = ctx.varDeclaration().type().getText();
        String name = ctx.varDeclaration().ID().getText();
        defineSymbol(name, type, ctx);
        if (ctx.varDeclaration().expression() != null) {
            String valueType = visit(ctx.varDeclaration().expression());
            checkAssignment(type, valueType, ctx);
        }
        String valueInfo = ctx.varDeclaration().expression() != null ? " avec l'assignation " + ctx.varDeclaration().expression().getText() : "";
        printIndent("Déclaration de variable: '" + name + "' de type '" + type + "'" + valueInfo);
        return null;
    }

    // --- Methods for evaluating expression types ---

    @Override
    public String visitNewObjectExpr(snlParser.NewObjectExprContext ctx) {
        String className = ctx.newExpr().ID().getText();
        printIndent("-> Vérification d'une instanciation : new " + className + "()");
        // The type of a 'new' expression is the class name.
        return className;
    }

    @Override
    public String visitLiteralExpr(snlParser.LiteralExprContext ctx) {
        if (ctx.literal().NUMBER() != null) return "integer";
        if (ctx.literal().FLOAT() != null) return "float";
        if (ctx.literal().STRING() != null) return "string";
        if (ctx.literal().BOOLEAN() != null) return "boolean";
        return "unknown_type";
    }

    @Override
    public String visitVarRef(snlParser.VarRefContext ctx) {
        String name = ctx.ID().getText();
        String type = findSymbol(name);
        if (type == null) {
            String error = "Semantic Error (line " + ctx.getStart().getLine() + "): Identifier '" + name + "' is not defined.";
            semanticErrors.add(error);
            return "error_type"; // Special type to prevent cascade errors
        }
        return type;
    }

    @Override
    public String visitParenExpr(snlParser.ParenExprContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public String visitPostfix(snlParser.PostfixContext ctx) {
        // Manually handle postfix expressions to correctly propagate types through member access chains (e.g., a.b.c)
        String currentType = visit(ctx.primary());

        for (snlParser.PostfixTailContext tail : ctx.postfixTail()) {
            if (currentType == null || currentType.equals("error_type")) {
                return "error_type"; // Stop processing if there's an error
            }

            if (tail instanceof snlParser.MemberAccessContext) {
                snlParser.MemberAccessContext memberCtx = (snlParser.MemberAccessContext) tail;
                String memberName = memberCtx.ID().getText();

                Map<String, String> members = typeDefinitions.get(currentType);
                if (members == null) {
                    semanticErrors.add("Semantic Error (line " + tail.getStart().getLine() + "): Type '" + currentType + "' is not a class or entity with members.");
                    return "error_type";
                }
                String memberType = members.get(memberName);
                if (memberType == null) {
                    semanticErrors.add("Semantic Error (line " + tail.getStart().getLine() + "): Member '" + memberName + "' not found in type '" + currentType + "'.");
                    return "error_type";
                }
                currentType = memberType; // The new type is the type of the member
            } else {
                semanticErrors.add("Semantic Error (line " + tail.getStart().getLine() + "): This type of member access (e.g., method calls, index access) is not yet supported.");
                return "error_type";
            }
        }
        return currentType;
    }

    @Override
    public String visitAssignment(snlParser.AssignmentContext ctx) {
        // In an assignment `a = b`, the type of the expression is the type of `b`.
        // We also need to check for type compatibility if it's an assignment.
        if (ctx.assignment() != null) { // This is an assignment like `x = y = 5`
            String lhsType = visit(ctx.logic_or());
            String rhsType = visit(ctx.assignment());
            checkAssignment(lhsType, rhsType, ctx);
            return lhsType;
        }
        return visit(ctx.logic_or());
    }

    @Override
    public String visitExprStmt(snlParser.ExprStmtContext ctx) {
        visit(ctx.expression());
        return null;
    }
}
