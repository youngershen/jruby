package org.jruby.compiler.ir;

import org.jruby.parser.StaticScope;

/**
 */
public class IRClassBody extends IRModuleBody {
    public IRClassBody(IRManager manager, IRScope lexicalParent, String name, int lineNumber, StaticScope scope) {
        super(manager, lexicalParent, name, lineNumber, scope);
    }

    public IRClassBody(IRManager manager, IRScope lexicalParent, String name, String fileName, int lineNumber, StaticScope scope) {
        super(manager, lexicalParent, name, fileName, lineNumber, scope);
    }

    @Override
    public String getScopeName() {
        return "ClassBody";
    }
}
