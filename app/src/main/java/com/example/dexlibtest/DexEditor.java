package com.example.dexlibtest;

import androidx.annotation.NonNull;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.ReferenceType;
import com.android.tools.smali.dexlib2.ValueType;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue;
import com.android.tools.smali.dexlib2.rewriter.ClassDefRewriter;
import com.android.tools.smali.dexlib2.rewriter.DexFileRewriter;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.EncodedValueRewriter;
import com.android.tools.smali.dexlib2.rewriter.FieldRewriter;
import com.android.tools.smali.dexlib2.rewriter.InstructionRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodImplementationRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodParameterRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.util.Random;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DexEditor {
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    public static final char[] DIGITS = "\u061c\u17b4\u17b5\u180b\u180c\u180d\u180e\ufe00\ufe01\ufe02\ufe03\ufe04\ufe05\ufe06\ufe07\ufe08\ufe09\ufe0a\ufe0b\ufe0c\ufe0d\ufe0e\ufe0f".toCharArray();
    private static final int ACC_ANNOTATION = 0x2000;

    private static final Random random = new Random();

    private final Map<String, String> mappings;
    private final Map<String, String> stringReplacements = new HashMap<>();
    private final List<String> annotationKeeps = new ArrayList<>();
    private final Opcodes opcodes = Opcodes.getDefault();
    private final DexBackedDexFile dex;
    private boolean obfuscate;
    private boolean shrink;

    public DexEditor(byte[] input) throws IOException {
        mappings =
                new HashMap<String, String>() {
                    @Override
                    public String get(Object key) {
                        String value = super.get(key);
                        if (value == null) value = (String) key;
                        return value;
                    }
                };
        dex = DexBackedDexFile.fromInputStream(opcodes, new ByteArrayInputStream(input));
    }

    private static String nextString() {
        return nextString(nextInt(16, 24));
    }

    private static String nextString(int length) {
        char[] digits = DexEditor.DIGITS;
        int digitsLength = digits.length;

        char[] chars = new char[length];
        for (int i = 0; length > i; i++) {
            chars[i] = digits[nextInt(0, digitsLength)];
        }

        return new String(chars);
    }

    private static String nextName(List<String> names) {
        String name = nextString();
        for (int i = 0; 8 > i; i++) {
            if (names.contains(name)) {
                name = name + nextString();
            } else break;
        }
        names.add(name);
        return name;
    }

    private static int nextInt(int a, int b) {
        return a + random.nextInt(b);
    }

    public Map<String, String> getMappings() {
        return mappings;
    }

    public void obfuscate(int minSdk) {
        String packageName = nextString();
        if (minSdk < 25) {
            char prefix = (char) ('a' + nextInt(0, 26));
            packageName = prefix + packageName;
        }
        ArrayList<String> names = new ArrayList<>(128);
        for (ClassDef classDef : dex.getClasses()) {
            String type = classDef.getType();
            String newType = type;
            if (!mappings.containsKey(type)) {
                newType = 'L' + packageName + '/' + nextName(names) + ';';
                mappings.put(type, newType);
            }
            if ((classDef.getAccessFlags() & ACC_ANNOTATION) != 0) {
                annotationKeeps.add(newType);
            }
        }
        obfuscate = true;
    }

    public void shrink() {
        shrink = true;
    }

    public void replaceType(String target, String replacement) {
        mappings.put(target, replacement);
    }

    public void replaceString(String target, String replacement) {
        stringReplacements.put(target, replacement);
    }

    public byte[] edit() throws IOException {
        RewriterModule module = getRewriterModule();
        DexFileRewriter rewriter = new DexFileRewriter(new DexRewriter(module));

        DexPool pool = new DexPool(opcodes);
        for (ClassDef classDef : rewriter.rewrite(dex).getClasses()) {
            pool.internClass(classDef);
        }

        MemoryDataStore output = new MemoryDataStore();
        pool.writeTo(output);
        return output.getData();
    }

    private RewriterModule getRewriterModule() {
        return new RewriterModule() {
            @NonNull
            @Override
            public Rewriter<ClassDef> getClassDefRewriter(@NonNull Rewriters rewriters) {
                return new ClassDefRewriter(rewriters) {
                    @NonNull
                    @Override
                    public ClassDef rewrite(@NonNull ClassDef classDef) {
                        return new RewrittenClassDef(classDef) {
                            @Override
                            public int getAccessFlags() {
                                int accessFlags = super.getAccessFlags();
                                if (obfuscate) {
                                    accessFlags |= Modifier.PUBLIC;
                                }
                                return accessFlags;
                            }

                            @Override
                            public String getSourceFile() {
                                if (shrink) {
                                    return null;
                                }
                                return super.getSourceFile();
                            }

                            @NonNull
                            @Override
                            public Set<? extends Annotation> getAnnotations() {
                                Set<? extends Annotation> annotations = super.getAnnotations();
                                if ((super.getAccessFlags() & ACC_ANNOTATION) == 0) {
                                    annotations = shrinkAnnotations(annotations);
                                }
                                return annotations;
                            }
                        };
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<Field> getFieldRewriter(@NonNull Rewriters rewriters) {
                return new FieldRewriter(rewriters) {
                    @NonNull
                    @Override
                    public Field rewrite(@NonNull Field field) {
                        return new RewrittenField(field) {
                            @Override
                            public int getAccessFlags() {
                                return getMemberAccessFlags(super.getAccessFlags());
                            }

                            @NonNull
                            @Override
                            public Set<? extends Annotation> getAnnotations() {
                                return shrinkAnnotations(super.getAnnotations());
                            }
                        };
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<Method> getMethodRewriter(@NonNull Rewriters rewriters) {
                return new MethodRewriter(rewriters) {
                    @NonNull
                    @Override
                    public Method rewrite(@NonNull Method method) {
                        return new RewrittenMethod(method) {
                            @Override
                            public int getAccessFlags() {
                                return getMemberAccessFlags(super.getAccessFlags());
                            }

                            @NonNull
                            @Override
                            public Set<? extends Annotation> getAnnotations() {
                                return shrinkAnnotations(super.getAnnotations());
                            }
                        };
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<MethodParameter> getMethodParameterRewriter(
                    @NonNull Rewriters rewriters) {
                return new MethodParameterRewriter(rewriters) {
                    @NonNull
                    @Override
                    public MethodParameter rewrite(@NonNull MethodParameter methodParameter) {
                        return new RewrittenMethodParameter(methodParameter) {
                            @NonNull
                            @Override
                            public Set<? extends Annotation> getAnnotations() {
                                return shrinkAnnotations(super.getAnnotations());
                            }

                            @Override
                            public String getName() {
                                if (shrink) {
                                    return null;
                                }
                                return super.getName();
                            }
                        };
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<MethodImplementation> getMethodImplementationRewriter(
                    @NonNull Rewriters rewriters) {
                return new MethodImplementationRewriter(rewriters) {
                    @NonNull
                    @Override
                    public MethodImplementation rewrite(
                            @NonNull MethodImplementation methodImplementation) {
                        return new RewrittenMethodImplementation(methodImplementation) {
                            @NonNull
                            @Override
                            public Iterable<? extends DebugItem> getDebugItems() {
                                if (shrink) {
                                    return Collections.emptyList();
                                }
                                return super.getDebugItems();
                            }
                        };
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<Instruction> getInstructionRewriter(@NonNull Rewriters rewriters) {
                return new InstructionRewriter(rewriters) {
                    @NonNull
                    @Override
                    public Instruction rewrite(@NonNull Instruction instruction) {
                        if (instruction instanceof ReferenceInstruction) {
                            switch (instruction.getOpcode().format) {
                                case Format21c:
                                    return new RewrittenInstruction21c(
                                            (Instruction21c) instruction) {
                                        @NonNull
                                        @Override
                                        public Reference getReference() {
                                            if (instruction.getReferenceType()
                                                    == ReferenceType.STRING) {
                                                String string =
                                                        ((StringReference)
                                                                        instruction.getReference())
                                                                .getString();
                                                String replacement = getStringReplacement(string);
                                                if (replacement != null) {
                                                    return new ImmutableStringReference(
                                                            replacement);
                                                }
                                            }
                                            return super.getReference();
                                        }
                                    };
                                case Format31c:
                                    return new RewrittenInstruction31c(
                                            (Instruction31c) instruction) {
                                        @NonNull
                                        @Override
                                        public Reference getReference() {
                                            if (instruction.getReferenceType()
                                                    == ReferenceType.STRING) {
                                                String string =
                                                        ((StringReference)
                                                                        instruction.getReference())
                                                                .getString();
                                                String replacement = getStringReplacement(string);
                                                if (replacement != null) {
                                                    return new ImmutableStringReference(
                                                            replacement);
                                                }
                                            }
                                            return super.getReference();
                                        }
                                    };
                                default:
                            }
                        }
                        return super.rewrite(instruction);
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<String> getTypeRewriter(@NonNull Rewriters rewriters) {
                return new TypeRewriter() {
                    @NonNull
                    @Override
                    protected String rewriteUnwrappedType(@NonNull String value) {
                        return mappings.get(value);
                    }
                };
            }

            @NonNull
            @Override
            public Rewriter<EncodedValue> getEncodedValueRewriter(@NonNull Rewriters rewriters) {
                return new EncodedValueRewriter(rewriters) {
                    @NonNull
                    @Override
                    public EncodedValue rewrite(@NonNull EncodedValue encodedValue) {
                        if (encodedValue.getValueType() == ValueType.STRING) {
                            String value = ((StringEncodedValue) encodedValue).getValue();
                            String replacement = getStringReplacement(value);
                            if (replacement != null) {
                                return new ImmutableStringEncodedValue(replacement);
                            }
                        }
                        return super.rewrite(encodedValue);
                    }
                };
            }

            public String getStringReplacement(String string) {
                return stringReplacements.get(string);
            }

            public int getMemberAccessFlags(int accessFlags) {
                if (obfuscate) {
                    accessFlags &= ~Modifier.PROTECTED;
                    if ((accessFlags & Modifier.PRIVATE) == 0) {
                        accessFlags |= Modifier.PUBLIC;
                    }
                }
                return accessFlags;
            }

            public Set<? extends Annotation> shrinkAnnotations(
                    Set<? extends Annotation> annotations) {
                if (annotations.isEmpty() || !shrink) {
                    return annotations;
                }
                Set<Annotation> newAnnotations = new HashSet<>();
                for (Annotation annotation : annotations) {
                    if (annotationKeeps.contains(annotation.getType())) {
                        newAnnotations.add(annotation);
                    }
                }
                return newAnnotations;
            }
        };
    }
}
