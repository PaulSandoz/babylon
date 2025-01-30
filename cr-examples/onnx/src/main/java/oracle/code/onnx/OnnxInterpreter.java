package oracle.code.onnx;

import oracle.code.onnx.ir.OnnxOp;
import oracle.code.onnx.ir.OnnxOps.*;

import java.util.List;

public class OnnxInterpreter {
    public static Object interpret(Class<? extends OnnxOp> opClass,
                                   List<Object> inputs,
                                   List<Object> attributes) {
        if (opClass == Add.class) {
            // @@@ type detection
            return new Tensor(OnnxRuntime.defaultEnvironment().runBinaryOp("Add", Tensor.ElementType.FLOAT, ((Tensor)inputs.get(0)).rtTensor, ((Tensor)inputs.get(1)).rtTensor));
        }
        throw new UnsupportedOperationException();
    }
}
