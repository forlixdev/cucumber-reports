package com.github.mkolisnyk.cucumber.runner;

import java.io.File;
import java.lang.reflect.Method;

import org.apache.commons.lang.ArrayUtils;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;

import com.github.mkolisnyk.cucumber.runner.parallel.CucumberRunnerThread;
import com.github.mkolisnyk.cucumber.runner.parallel.CucumberRunnerThreadPool;

import cucumber.api.CucumberOptions;
import cucumber.api.SnippetType;
import javassist.ClassPool;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.ArrayMemberValue;
import javassist.bytecode.annotation.BooleanMemberValue;
import javassist.bytecode.annotation.EnumMemberValue;
import javassist.bytecode.annotation.IntegerMemberValue;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.StringMemberValue;

public class ExtendedParallelCucumber extends Runner {
    private Class<?> clazz;
    private ExtendedCucumberOptions[] options;
    private CucumberOptions cucumberOption;
    private int threadsCount = 1;
    private ExtendedCucumber[] runners;

    public ExtendedParallelCucumber(Class<?> clazzValue) throws Exception {
        super();
        this.clazz = clazzValue;
        this.options = clazz.getAnnotationsByType(ExtendedCucumberOptions.class);
        this.cucumberOption = clazz.getAnnotation(CucumberOptions.class);
        for (ExtendedCucumberOptions option : options) {
            threadsCount = Math.max(threadsCount , option.threadsCount());
        }
        this.runners = buildRunners();
    }
    private ExtendedCucumber[] buildRunners() throws Exception {
        CucumberOptions[] cucumberOptions = this.splitCucumberOption(this.cucumberOption);
        ExtendedCucumberOptions[][] extendedOptions = this.splitExtendedCucumberOptions(this.options, cucumberOptions.length);
        return generateTestClasses(cucumberOptions, extendedOptions);
    }

    public final ExtendedCucumber[] getRunners() {
        return runners;
    }
    private String[] getFileNames(String rootFolder) throws Exception {
        String[] fileNames = {};
        for (File file : (new File(rootFolder)).listFiles()) {
            if (file.isDirectory()) {
                fileNames = (String[]) ArrayUtils.addAll(fileNames, getFileNames(file.getAbsolutePath()));
            } else {
                fileNames = (String[]) ArrayUtils.add(fileNames, file.getAbsolutePath());
            }
        }
        return fileNames;
    }
    private String[] getFilesByMask(String startFolder, String mask) throws Exception {
        String[] result = {};
        String[] input = getFileNames(startFolder);
        for (String fileName : input) {
            if (fileName.matches(mask)) {
                result = (String[]) ArrayUtils.add(result, fileName);
            }
        }
        return result;
    }

    public MemberValue getFieldMemberValue(Object object, Method field) throws Exception {
        ConstPool cp = new ConstPool(this.getClass().getCanonicalName());
        if (field.getReturnType().isArray()) {
            if (field.getReturnType().getComponentType().equals(String.class)) {
                ArrayMemberValue array = new ArrayMemberValue(new StringMemberValue(cp), cp);
                String[] annoValues = (String[]) field.invoke(object);
                StringMemberValue[] values = new StringMemberValue[annoValues.length];
                for (int i = 0; i < annoValues.length; i++) {
                    values[i] = new StringMemberValue(annoValues[i], cp);
                }
                array.setValue(values);
                return array;
            } else {
                ArrayMemberValue array = new ArrayMemberValue(new StringMemberValue(cp), cp);
                return array;
            }
        }
        if (field.getReturnType().equals(boolean.class)) {
            return new BooleanMemberValue((Boolean) field.invoke(object), cp);
        }
        if (field.getReturnType().equals(String.class)) {
            return new StringMemberValue((String) field.invoke(object), cp);
        }
        if (field.getReturnType().equals(int.class)) {
            return new IntegerMemberValue(cp, (int) field.invoke(object));
        }
        return null;
    }
    public String[] convertPluginPaths(String[] original, int index) {
        String[] result = new String[original.length];
        for (int i = 0; i < original.length; i++) {
            File path = new File(original[i].replaceFirst("^(usage|junit|json|html|pretty):", ""));
            String name = path.getName();
            String location = path.getParent();
            result[i] = location + "/" + index + "/" + name;
            result[i] = original[i].replaceFirst("^(usage|junit|json|html|pretty):(.*)$", "$1:" + result[i]);
        }
        return result;
    }
    public CucumberOptions[] splitCucumberOption(CucumberOptions option) throws Exception {
        CucumberOptions[] result = {};
        String[] featurePaths = option.features();
        String[] featureFiles = new String[] {};
        for (String featurePath : featurePaths) {
            File feature = new File(featurePath);
            if (feature.isDirectory()) {
                featureFiles = (String[]) ArrayUtils.addAll(
                        featureFiles, getFilesByMask(feature.getAbsolutePath(), "(.*).feature"));
            } else {
                featureFiles = (String[]) ArrayUtils.add(featureFiles, feature.getAbsolutePath());
            }
        }
        for (String file : featureFiles) {
            ConstPool cp = new ConstPool(ExtendedParallelCucumber.class.getCanonicalName());
            Annotation anno = new Annotation(CucumberOptions.class.getCanonicalName(), cp);
            int index = 0;
            for (Method field : CucumberOptions.class.getDeclaredMethods()) {
                String name = field.getName();
                if (name.equals("features")) {
                    ArrayMemberValue array = new ArrayMemberValue(new StringMemberValue(cp), cp);
                    array.setValue(new StringMemberValue[] {new StringMemberValue(file, cp)});
                    anno.addMemberValue(name, array);
                } else if (name.equals("plugin")) {
                    String[] plugin = convertPluginPaths(option.plugin(), index);
                    ArrayMemberValue array = new ArrayMemberValue(new StringMemberValue(cp), cp);
                    StringMemberValue[] values = new StringMemberValue[plugin.length];
                    for (int i = 0; i < plugin.length; i++) {
                        values[i] = new StringMemberValue(plugin[i], cp);
                    }
                    array.setValue(values);
                    anno.addMemberValue(name, array);
                } else if (name.equals("snippets")) {
                    EnumMemberValue value = new EnumMemberValue(cp);
                    value.setType(SnippetType.class.getCanonicalName());
                    value.setValue(SnippetType.UNDERSCORE.name());
                    anno.addMemberValue(name, value);
                } else {
                    MemberValue value = getFieldMemberValue(option, field);
                    if (value != null) {
                        anno.addMemberValue(name, value);
                    }
                }
                index++;
            }
            CucumberOptions newOption = (CucumberOptions) anno.toAnnotationType(
                    this.getClass().getClassLoader(), ClassPool.getDefault());
            result = (CucumberOptions[]) ArrayUtils.add(result, newOption);
        }
        return result;
    }
    public ExtendedCucumberOptions[][] splitExtendedCucumberOptions(
            ExtendedCucumberOptions[] extendedOptions,
            int suitesCount) throws Exception {
        ExtendedCucumberOptions[][] result = new ExtendedCucumberOptions[suitesCount][extendedOptions.length];
        for (int i = 0; i < suitesCount; i++) {
            ConstPool cp = new ConstPool(ExtendedParallelCucumber.class.getCanonicalName());
            for (int j = 0; j < extendedOptions.length; j++) {
                Annotation anno = new Annotation(ExtendedCucumberOptions.class.getCanonicalName(), cp);
                for (Method field : ExtendedCucumberOptions.class.getDeclaredMethods()) {
                    String name = field.getName();
                    if (name.equals("outputFolder")) {
                        anno.addMemberValue(name,
                            new StringMemberValue(extendedOptions[j].outputFolder() + "/" + i + "_" + j, cp));
                    } else if (name.equals("jsonReport") || name.equals("jsonUsageReport")) {
                        String newName = this.convertPluginPaths(
                            new String[] {(String) field.invoke(extendedOptions[j])}, i)[0];
                        anno.addMemberValue(name,
                                new StringMemberValue(newName, cp));
                    } else if (name.equals("jsonReports") || name.equals("jsonUsageReports")) {
                        String[] reports = convertPluginPaths((String[]) field.invoke(extendedOptions[j]), i);
                        ArrayMemberValue array = new ArrayMemberValue(new StringMemberValue(cp), cp);
                        StringMemberValue[] values = new StringMemberValue[reports.length];
                        for (int k = 0; k < reports.length; k++) {
                            values[k] = new StringMemberValue(reports[k], cp);
                        }
                        array.setValue(values);
                        anno.addMemberValue(name, array);
                    } else {
                        MemberValue value = getFieldMemberValue(extendedOptions[j], field);
                        if (value != null) {
                            anno.addMemberValue(name, getFieldMemberValue(extendedOptions[j], field));
                        }
                    }
                }
                result[i][j] = (ExtendedCucumberOptions) anno.toAnnotationType(
                        this.getClass().getClassLoader(), ClassPool.getDefault());
                //result[i][j] = anno;
            }

        }
        return result;
    }
    public ExtendedCucumber[] generateTestClasses(CucumberOptions[] cucumberOptions,
            ExtendedCucumberOptions[][] extendedOptions) throws Exception {
        ExtendedCucumber[] classes = new ExtendedCucumber[cucumberOptions.length];
        for (int i = 0; i < cucumberOptions.length; i++) {
            classes[i] = new ExtendedCucumber(this.clazz, cucumberOptions[i], extendedOptions[i]);
        }
        return classes;
    }
    @Override
    public Description getDescription() {
        return null;
    }

    @Override
    public void run(RunNotifier notifier) {
        CucumberRunnerThreadPool.setCapacity(this.threadsCount);
        try {
            for (ExtendedCucumber runner : this.getRunners()) {
                Thread thread = new Thread(new CucumberRunnerThread(runner, notifier));
                CucumberRunnerThreadPool.get().push(thread);
            }
            CucumberRunnerThreadPool.get().waitEmpty();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
