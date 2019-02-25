package com.mageddo.rawstringliterals;

import com.mageddo.rawstringliterals.javac.ClassScanner;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;

import javax.tools.JavaFileObject;

public class MyTreePathScanner extends TreePathScanner<Object, CompilationUnitTree> {

	private final TreeMaker maker;
	private final Trees trees;
	private final ClassSymbol classSymbol;

	public MyTreePathScanner(TreeMaker maker, Trees trees, ClassSymbol classSymbol) {
		this.maker = maker;
		this.trees = trees;
		this.classSymbol = classSymbol;
	}

	@Override
	public Trees visitClass(final ClassTree classTree, final CompilationUnitTree unitTree) {

		if (unitTree instanceof JCCompilationUnit) {

			final JCCompilationUnit compilationUnit = (JCCompilationUnit) unitTree;
			if (compilationUnit.sourcefile.getKind() != JavaFileObject.Kind.SOURCE) {
				return trees;
			}
			compilationUnit.accept(new TreeTranslator() {
				@Override
				public void visitMethodDef(JCTree.JCMethodDecl jcMethodDecl) {
					super.visitMethodDef(jcMethodDecl);
					for (final JCStatement statement : jcMethodDecl.getBody().getStatements()) {
						if(!(statement instanceof JCVariableDecl)){
							continue;
						}
						final JCVariableDecl variableDecl = (JCVariableDecl) statement;
						for (final JCAnnotation annotation : variableDecl.mods.annotations) {
							if(annotation.getAnnotationType() instanceof JCIdent) {
								final JCIdent annotationType = (JCIdent) annotation.getAnnotationType();
								final String annotationName = RawString.class.getSimpleName();
								if(annotationType.getName().toString().equals(annotationName)){
									final String varValue = ClassScanner.findVarValue(
										classSymbol, jcMethodDecl.getName().toString(), variableDecl.getName().toString(), annotationName
									);
									variableDecl.init = maker.Literal(varValue);
								}
							}
						}
					}
				}
			});
		}
		return trees;
	}

	public void scan() {
		final TreePath path = trees.getPath(classSymbol);
		this.scan(path, path.getCompilationUnit());
	}
}
