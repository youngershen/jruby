/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jruby.compiler.ir.instructions.jruby;

import java.util.Map;
import org.jruby.RubyClass;
import org.jruby.compiler.ir.Operation;
import org.jruby.compiler.ir.instructions.Instr;
import org.jruby.compiler.ir.operands.Operand;
import org.jruby.compiler.ir.operands.StringLiteral;
import org.jruby.compiler.ir.operands.Variable;
import org.jruby.compiler.ir.representations.InlinerInfo;
import org.jruby.compiler.ir.targets.JVM;
import org.jruby.runtime.Block;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;

/**
 *
 * @author enebo
 */
public class MethodIsPublicInstr extends DefinedObjectNameInstr {
    public MethodIsPublicInstr(Variable result, Operand object, StringLiteral name) {
        super(Operation.METHOD_IS_PUBLIC, result, new Operand[] { object, name });
    }

    @Override
    public Instr cloneForInlining(InlinerInfo inlinerInfo) {
        return new MethodIsPublicInstr((Variable) getResult().cloneForInlining(inlinerInfo), 
                getObject().cloneForInlining(inlinerInfo),
                (StringLiteral) getName().cloneForInlining(inlinerInfo));
    }

    // ENEBO: searchMethod on bad name returns undefined method...so we use that visibility?
    private boolean isPublic(IRubyObject object, String name) {
        RubyClass metaClass = object.getMetaClass();
        Visibility  visibility   = metaClass.searchMethod(name).getVisibility();
        
        return visibility != null && !visibility.isPrivate() && 
                !(visibility.isProtected() && metaClass.getRealClass().isInstance(object));
    }

    @Override
    public Object interpret(ThreadContext context, DynamicScope currDynScope, IRubyObject self, Object[] temp, Block block) {
        IRubyObject receiver = (IRubyObject) getObject().retrieve(context, self, currDynScope, temp);
        
        return context.runtime.newBoolean(isPublic(receiver, getName().string));        
    }

    @Override
    public void compile(JVM jvm) {
        // no-op right now
    }    
}
