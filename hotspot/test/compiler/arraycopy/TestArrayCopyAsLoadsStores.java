/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 6912521
 * @summary small array copy as loads/stores
 * @run main/othervm -XX:-BackgroundCompilation -XX:-UseOnStackReplacement -XX:CompileCommand=dontinline,TestArrayCopyAsLoadsStores::m* -XX:TypeProfileLevel=200 TestArrayCopyAsLoadsStores
 * @run main/othervm -XX:-BackgroundCompilation -XX:-UseOnStackReplacement -XX:CompileCommand=dontinline,TestArrayCopyAsLoadsStores::m* -XX:+IgnoreUnrecognizedVMOptions -XX:+StressArrayCopyMacroNode -XX:TypeProfileLevel=200 TestArrayCopyAsLoadsStores
 *
 */

import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

public class TestArrayCopyAsLoadsStores {

    public enum ArraySrc {
        SMALL,
        LARGE,
        ZERO
    }

    public enum ArrayDst {
        NONE,
        NEW,
        SRC
    }

    static class A {
    }

    static class B extends A {
    }

    static final A[] small_a_src = new A[5];
    static final A[] large_a_src = new A[10];
    static final A[] zero_a_src = new A[0];
    static final int[] small_int_src = new int[5];
    static final int[] large_int_src = new int[10];
    static final int[] zero_int_src = new int[0];
    static final Object[] small_object_src = new Object[5];
    static Object src;

    @Retention(RetentionPolicy.RUNTIME)
    @interface Args {
        ArraySrc src();
        ArrayDst dst() default ArrayDst.NONE;
        int[] extra_args() default {};
    }

    // array clone should be compiled as loads/stores
    @Args(src=ArraySrc.SMALL)
    static A[] m1() throws CloneNotSupportedException {
        return (A[])small_a_src.clone();
    }

    @Args(src=ArraySrc.SMALL)
    static int[] m2() throws CloneNotSupportedException {
        return (int[])small_int_src.clone();
    }

    // new array allocation should be optimized out
    @Args(src=ArraySrc.SMALL)
    static int m3() throws CloneNotSupportedException {
        int[] array_clone = (int[])small_int_src.clone();
        return array_clone[0] + array_clone[1] + array_clone[2] +
            array_clone[3] + array_clone[4];
    }

    // should not be compiled as loads/stores
    @Args(src=ArraySrc.LARGE)
    static int[] m4() throws CloneNotSupportedException {
        return (int[])large_int_src.clone();
    }

    // check that array of length 0 is handled correctly
    @Args(src=ArraySrc.ZERO)
    static int[] m5() throws CloneNotSupportedException {
        return (int[])zero_int_src.clone();
    }

    // array copy should be compiled as loads/stores
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NEW)
    static void m6(int[] src, int[] dest) {
        System.arraycopy(src, 0, dest, 0, 5);
    }

    // array copy should not be compiled as loads/stores
    @Args(src=ArraySrc.LARGE, dst=ArrayDst.NEW)
    static void m7(int[] src, int[] dest) {
        System.arraycopy(src, 0, dest, 0, 10);
    }

    // array copy should be compiled as loads/stores
    @Args(src=ArraySrc.SMALL)
    static A[] m8(A[] src) {
        src[0] = src[0]; // force null check
        A[] dest = new A[5];
        System.arraycopy(src, 0, dest, 0, 5);
        return dest;
    }

    // array copy should not be compiled as loads/stores: we would
    // need to emit GC barriers
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NEW)
    static void m9(A[] src, A[] dest) {
        System.arraycopy(src, 0, dest, 0, 5);
    }

    // overlapping array regions: copy backward
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.SRC)
    static void m10(int[] src, int[] dest) {
        System.arraycopy(src, 0, dest, 1, 4);
    }

    static boolean m10_check(int[] src, int[] dest) {
        boolean failure = false;
        for (int i = 0; i < 5; i++) {
            int j = Math.max(i - 1, 0);
            if (dest[i] != src[j]) {
                System.out.println("Test m10 failed for " + i + " src[" + j +"]=" + src[j] + ", dest[" + i + "]=" + dest[i]);
                failure = true;
            }
        }
        return failure;
    }

    // overlapping array regions: copy forward
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.SRC)
    static void m11(int[] src, int[] dest) {
        System.arraycopy(src, 1, dest, 0, 4);
    }

    static boolean m11_check(int[] src, int[] dest) {
        boolean failure = false;
        for (int i = 0; i < 5; i++) {
            int j = Math.min(i + 1, 4);
            if (dest[i] != src[j]) {
                System.out.println("Test m11 failed for " + i + " src[" + j +"]=" + src[j] + ", dest[" + i + "]=" + dest[i]);
                failure = true;
            }
        }
        return failure;
    }

    // overlapping array region with unknown src/dest offsets: compiled code must include both forward and backward copies
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.SRC, extra_args={0,1})
    static void m12(int[] src, int[] dest, int srcPos, int destPos) {
        System.arraycopy(src, srcPos, dest, destPos, 4);
    }

    static boolean m12_check(int[] src, int[] dest) {
        boolean failure = false;
        for (int i = 0; i < 5; i++) {
            int j = Math.max(i - 1, 0);
            if (dest[i] != src[j]) {
                System.out.println("Test m10 failed for " + i + " src[" + j +"]=" + src[j] + ", dest[" + i + "]=" + dest[i]);
                failure = true;
            }
        }
        return failure;
    }

    // Array allocation and copy should optimize out
    @Args(src=ArraySrc.SMALL)
    static int m13(int[] src) {
        int[] dest = new int[5];
        System.arraycopy(src, 0, dest, 0, 5);
        return dest[0] + dest[1] + dest[2] + dest[3] + dest[4];
    }

    // Check that copy of length 0 is handled correctly
    @Args(src=ArraySrc.ZERO, dst=ArrayDst.NEW)
    static void m14(int[] src, int[] dest) {
        System.arraycopy(src, 0, dest, 0, 0);
    }

    // copyOf should compile to loads/stores
    @Args(src=ArraySrc.SMALL)
    static A[] m15() {
        return Arrays.copyOf(small_a_src, 5, A[].class);
    }

    static Object[] helper16(int i) {
        Object[] arr = null;
        if ((i%2) == 0) {
            arr = small_a_src;
        } else {
            arr = small_object_src;
        }
        return arr;
    }

    // CopyOf may need subtype check
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NONE, extra_args={0})
    static A[] m16(A[] unused_src, int i) {
        Object[] arr = helper16(i);
        return Arrays.copyOf(arr, 5, A[].class);
    }

    static Object[] helper17_1(int i) {
        Object[] arr = null;
        if ((i%2) == 0) {
            arr = small_a_src;
        } else {
            arr = small_object_src;
        }
        return arr;
    }

    static A[] helper17_2(Object[] arr) {
        return Arrays.copyOf(arr, 5, A[].class);
    }

    // CopyOf may leverage type speculation
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NONE, extra_args={0})
    static A[] m17(A[] unused_src, int i) {
        Object[] arr = helper17_1(i);
        return helper17_2(arr);
    }

    static Object[] helper18_1(int i) {
        Object[] arr = null;
        if ((i%2) == 0) {
            arr = small_a_src;
        } else {
            arr = small_object_src;
        }
        return arr;
    }

    static Object[] helper18_2(Object[] arr) {
        return Arrays.copyOf(arr, 5, Object[].class);
    }

    // CopyOf should not attempt to use type speculation if it's not needed
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NONE, extra_args={0})
    static Object[] m18(A[] unused_src, int i) {
        Object[] arr = helper18_1(i);
        return helper18_2(arr);
    }

    static Object[] helper19(int i) {
        Object[] arr = null;
        if ((i%2) == 0) {
            arr = small_a_src;
        } else {
            arr = small_object_src;
        }
        return arr;
    }

    // CopyOf may need subtype check. Test is run to make type check
    // fail and cause deoptimization. Next compilation should not
    // compile as loads/stores because the first compilation
    // deoptimized.
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NONE, extra_args={0})
    static A[] m19(A[] unused_src, int i) {
        Object[] arr = helper19(i);
        return Arrays.copyOf(arr, 5, A[].class);
    }

    // copyOf for large array should not compile to loads/stores
    @Args(src=ArraySrc.LARGE)
    static A[] m20() {
        return Arrays.copyOf(large_a_src, 10, A[].class);
    }

    // check zero length copyOf is handled correctly
    @Args(src=ArraySrc.ZERO)
    static A[] m21() {
        return Arrays.copyOf(zero_a_src, 0, A[].class);
    }

    // Run with srcPos=0 for a 1st compile, then with incorrect value
    // of srcPos to cause deoptimization, then with srcPos=0 for a 2nd
    // compile. The 2nd compile shouldn't turn arraycopy into
    // loads/stores because input arguments are no longer known to be
    // valid.
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.NEW, extra_args={0})
    static void m22(int[] src, int[] dest, int srcPos) {
        System.arraycopy(src, srcPos, dest, 0, 5);
    }

    // copyOfRange should compile to loads/stores
    @Args(src=ArraySrc.SMALL)
    static A[] m23() {
        return Arrays.copyOfRange(small_a_src, 1, 4, A[].class);
    }

    static boolean m23_check(A[] src, A[] dest) {
        boolean failure = false;
        for (int i = 0; i < 3; i++) {
            if (src[i+1] != dest[i]) {
                System.out.println("Test m23 failed for " + i + " src[" + (i+1) +"]=" + dest[i] + ", dest[" + i + "]=" + dest[i]);
                failure = true;
            }
        }
        return failure;
    }

    // array copy should be compiled as loads/stores. Invoke then with
    // incompatible array type to verify we don't allow a forbidden
    // arraycopy to happen.
    @Args(src=ArraySrc.SMALL)
    static A[] m24(Object[] src) {
        src[0] = src[0]; // force null check
        A[] dest = new A[5];
        System.arraycopy(src, 0, dest, 0, 5);
        return dest;
    }

    // overlapping array region with unknown src/dest offsets but
    // length 1: compiled code doesn't need both forward and backward
    // copies
    @Args(src=ArraySrc.SMALL, dst=ArrayDst.SRC, extra_args={0,1})
    static void m25(int[] src, int[] dest, int srcPos, int destPos) {
        System.arraycopy(src, srcPos, dest, destPos, 1);
    }

    static boolean m25_check(int[] src, int[] dest) {
        boolean failure = false;
        if (dest[1] != src[0]) {
            System.out.println("Test m10 failed for src[0]=" + src[0] + ", dest[1]=" + dest[1]);
            return true;
        }
        return false;
    }

    final HashMap<String,Method> tests = new HashMap<>();
    {
        for (Method m : this.getClass().getDeclaredMethods()) {
            if (m.getName().matches("m[0-9]+(_check)?")) {
                assert(Modifier.isStatic(m.getModifiers())) : m;
                tests.put(m.getName(), m);
            }
        }
    }

    boolean success = true;

    void doTest(String name) throws Exception {
        Method m = tests.get(name);
        Method m_check = tests.get(name + "_check");
        Class[] paramTypes = m.getParameterTypes();
        Object[] params = new Object[paramTypes.length];
        Class retType = m.getReturnType();
        boolean isIntArray = (retType.isPrimitive() && !retType.equals(Void.TYPE)) ||
            (retType.equals(Void.TYPE) && paramTypes[0].getComponentType().isPrimitive()) ||
            (retType.isArray() && retType.getComponentType().isPrimitive());

        Args args = m.getAnnotation(Args.class);

        Object src = null;
        switch(args.src()) {
        case SMALL: {
            if (isIntArray) {
                src = small_int_src;
            } else {
                src = small_a_src;
            }
            break;
        }
        case LARGE: {
            if (isIntArray) {
                src = large_int_src;
            } else {
                src = large_a_src;
            }
            break;
        }
        case ZERO: {
            assert isIntArray;
            if (isIntArray) {
                src = zero_int_src;
            } else {
                src = zero_a_src;
            }
            break;
        }
        }

        for (int i = 0; i < 20000; i++) {
            boolean failure = false;

            int p = 0;

            if (params.length > 0) {
                if (isIntArray) {
                    params[0] = ((int[])src).clone();
                } else {
                    params[0] = ((A[])src).clone();
                }
                p++;
            }

            if (params.length > 1) {
                switch(args.dst()) {
                case NEW: {
                    if (isIntArray) {
                        params[1] = new int[((int[])params[0]).length];
                    } else {
                        params[1] = new A[((A[])params[0]).length];
                    }
                    p++;
                    break;
                }
                case SRC: {
                    params[1] = params[0];
                    p++;
                    break;
                }
                case NONE: break;
                }
            }

            for (int j = 0; j < args.extra_args().length; j++) {
                params[p+j] = args.extra_args()[j];
            }

            Object res = m.invoke(null, params);

            if (retType.isPrimitive() && !retType.equals(Void.TYPE)) {
                int s = (int)res;
                int sum = 0;
                int[] int_res = (int[])src;
                for (int j = 0; j < int_res.length; j++) {
                    sum += int_res[j];
                }
                failure = (s != sum);
                if (failure) {
                    System.out.println("Test " + name + " failed: result = " + s + " != " + sum);
                }
            } else {
                Object dest = null;
                if (!retType.equals(Void.TYPE)) {
                    dest = res;
                } else {
                    dest = params[1];
                }

                if (m_check != null) {
                    failure = (boolean)m_check.invoke(null,  new Object[] { src, dest });
                } else {
                    if (isIntArray) {
                        int[] int_res = (int[])src;
                        int[] int_dest = (int[])dest;
                        for (int j = 0; j < int_res.length; j++) {
                            if (int_res[j] != int_dest[j]) {
                                System.out.println("Test " + name + " failed for " + j + " src[" + j +"]=" + int_res[j] + ", dest[" + j + "]=" + int_dest[j]);
                                failure = true;
                            }
                        }
                    } else {
                        Object[] object_res = (Object[])src;
                        Object[] object_dest = (Object[])dest;
                        for (int j = 0; j < object_res.length; j++) {
                            if (object_res[j] != object_dest[j]) {
                                System.out.println("Test " + name + " failed for " + j + " src[" + j +"]=" + object_res[j] + ", dest[" + j + "]=" + object_dest[j]);
                                failure = true;
                            }
                        }
                    }
                }
            }

            if (failure) {
                success = false;
                break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < small_a_src.length; i++) {
            small_a_src[i] = new A();
        }

        for (int i = 0; i < small_int_src.length; i++) {
            small_int_src[i] = i;
        }

        for (int i = 0; i < large_int_src.length; i++) {
            large_int_src[i] = i;
        }

        for (int i = 0; i < 5; i++) {
            small_object_src[i] = new Object();
        }

        TestArrayCopyAsLoadsStores test = new TestArrayCopyAsLoadsStores();

        test.doTest("m1");
        test.doTest("m2");
        test.doTest("m3");
        test.doTest("m4");
        test.doTest("m5");
        test.doTest("m6");
        test.doTest("m7");
        test.doTest("m8");
        test.doTest("m9");
        test.doTest("m10");
        test.doTest("m11");
        test.doTest("m12");
        test.doTest("m13");
        test.doTest("m14");
        test.doTest("m15");

        // make both branches of the If appear taken
        for (int i = 0; i < 20000; i++) {
            helper16(i);
        }

        test.doTest("m16");

        // load class B so type check in m17 would not be simple comparison
        B b = new B();
        // make both branches of the If appear taken
        for (int i = 0; i < 20000; i++) {
            helper17_1(i);
        }

        test.doTest("m17");

        // make both branches of the If appear taken
        for (int i = 0; i < 20000; i++) {
            helper18_1(i);
        }
        test.doTest("m18");

        // make both branches of the If appear taken
        for (int i = 0; i < 20000; i++) {
            helper19(i);
        }

        // Compile
        for (int i = 0; i < 20000; i++) {
            m19(null, 0);
        }

        // force deopt
        boolean m19_exception = false;
        for (int i = 0; i < 10; i++) {
            try {
                m19(null, 1);
            } catch(ArrayStoreException ase) {
                m19_exception = true;
            }
        }

        if (!m19_exception) {
            System.out.println("Test m19: exception wasn't thrown");
            test.success = false;
        }

        test.doTest("m19");

        test.doTest("m20");
        test.doTest("m21");

        // Compile
        int[] dst = new int[small_int_src.length];
        for (int i = 0; i < 20000; i++) {
            m22(small_int_src, dst, 0);
        }

        // force deopt
        for (int i = 0; i < 10; i++) {
            try {
                m22(small_int_src, dst, 5);
            } catch(ArrayIndexOutOfBoundsException aioobe) {}
        }

        test.doTest("m22");
        test.doTest("m23");

        test.doTest("m24");
        boolean m24_exception = false;
        try {
            m24(small_object_src);
        } catch(ArrayStoreException ase) {
            m24_exception = true;
        }

        if (!m24_exception) {
            System.out.println("Test m24: exception wasn't thrown");
            test.success = false;
        }

        test.doTest("m25");

        if (!test.success) {
            throw new RuntimeException("some tests failed");
        }
    }
}
