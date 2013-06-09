/**
 *  Copyright 2012-2013 Gunnar Morling (http://www.gunnarmorling.de/)
 *  and/or other contributors as indicated by the @authors tag. See the
 *  copyright.txt file in the distribution for a full listing of all
 *  contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.mapstruct.ap;

import java.beans.Introspector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementKindVisitor6;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

import org.mapstruct.ap.conversion.Conversion;
import org.mapstruct.ap.conversion.Conversions;
import org.mapstruct.ap.model.BeanMapping;
import org.mapstruct.ap.model.Mapper;
import org.mapstruct.ap.model.MappingMethod;
import org.mapstruct.ap.model.Options;
import org.mapstruct.ap.model.PropertyMapping;
import org.mapstruct.ap.model.Type;
import org.mapstruct.ap.model.source.MappedProperty;
import org.mapstruct.ap.model.source.Mapping;
import org.mapstruct.ap.model.source.Method;
import org.mapstruct.ap.model.source.Parameter;
import org.mapstruct.ap.util.Executables;
import org.mapstruct.ap.util.Filters;
import org.mapstruct.ap.util.TypeUtil;
import org.mapstruct.ap.writer.ModelWriter;

import static javax.lang.model.util.ElementFilter.methodsIn;

/**
 * An {@link ElementVisitor} which generates the implementations for mapper
 * interfaces (interfaces annotated with {@code @Mapper}.
 * </p>
 * Implementation notes:
 * </p>
 * The mapper generation happens by building up a model representation of
 * the mapper to be generated (a {@link Mapper} object), which is then written
 * into a file using the FreeMarker template engine.
 * </p>
 * The model instantiation happens in two phases/passes: The first one retrieves
 * the mapping methods of the given interfaces and their configuration (the
 * <i>source</i> model). In the second pass the individual methods are
 * aggregated into the <i>target</i> model, which contains a {@link BeanMapping}
 * each pair of source and target type which has references to forward and
 * reverse mapping methods as well as the methods for mapping the element types
 * (if it is a collection mapping) and {@link Conversion}s if applicable.
 * </p>
 * For reading annotation attributes, prisms as generated with help of the <a
 * href="https://java.net/projects/hickory">Hickory</a> tool are used. These
 * prisms allow a comfortable access to annotations and their attributes without
 * depending on their class objects.
 *
 * @author Gunnar Morling
 */
public class MapperGenerationVisitor extends ElementKindVisitor6<Void, Void> {

    private static final String IMPLEMENTATION_SUFFIX = "Impl";

    private final ProcessingEnvironment processingEnvironment;
    private final Types typeUtils;
    private final Elements elementUtils;
    private final TypeUtil typeUtil;
    private final Options options;

    private boolean mappingErroneous = false;

    public MapperGenerationVisitor(ProcessingEnvironment processingEnvironment, Options options) {
        this.processingEnvironment = processingEnvironment;
        this.typeUtils = processingEnvironment.getTypeUtils();
        this.elementUtils = processingEnvironment.getElementUtils();
        this.typeUtil = new TypeUtil( elementUtils, typeUtils );
        this.options = options;
    }

    @Override
    public Void visitTypeAsInterface(TypeElement element, Void p) {
        Mapper model = retrieveModel( element );

        if ( !mappingErroneous ) {
            String sourceFileName = element.getQualifiedName() + IMPLEMENTATION_SUFFIX;
            writeModelToSourceFile( sourceFileName, model );
        }

        return null;
    }

    private void writeModelToSourceFile(String fileName, Mapper model) {
        JavaFileObject sourceFile;
        try {
            sourceFile = processingEnvironment.getFiler().createSourceFile( fileName );
        }
        catch ( IOException e ) {
            throw new RuntimeException( e );
        }

        ModelWriter modelWriter = new ModelWriter( "mapper-implementation.ftl" );
        modelWriter.writeModel( sourceFile, model );
    }

    private Mapper retrieveModel(TypeElement element) {
        List<Method> methods = retrieveMethods( null, element );
        List<BeanMapping> mappings = getMappings( methods );
        List<Type> usedMapperTypes = getUsedMapperTypes( element );

        Mapper mapper = new Mapper(
            elementUtils.getPackageOf( element ).getQualifiedName().toString(),
            element.getSimpleName().toString(),
            element.getSimpleName() + IMPLEMENTATION_SUFFIX,
            mappings,
            usedMapperTypes,
            options
        );

        return mapper;
    }

    private List<BeanMapping> getMappings(List<Method> methods) {
        Conversions conversions = new Conversions( elementUtils, typeUtils, typeUtil );

        List<BeanMapping> mappings = new ArrayList<BeanMapping>();
        Set<Method> processedMethods = new HashSet<Method>();

        for ( Method method : methods ) {
            if ( processedMethods.contains( method ) ) {
                continue;
            }

            MappingMethod mappingMethod = new MappingMethod(
                method.getDeclaringMapper(),
                method.getName(),
                method.getParameterName(),
                getElementMappingMethod( methods, method )
            );

            MappingMethod reverseMappingMethod = null;
            Method rawReverseMappingMethod = getReverseMappingMethod( methods, method );
            if ( rawReverseMappingMethod != null ) {
                processedMethods.add( rawReverseMappingMethod );

                reverseMappingMethod = new MappingMethod(
                    rawReverseMappingMethod.getDeclaringMapper(),
                    rawReverseMappingMethod.getName(),
                    rawReverseMappingMethod.getParameterName(),
                    getElementMappingMethod( methods, rawReverseMappingMethod )
                );
            }

            List<PropertyMapping> propertyMappings = new ArrayList<PropertyMapping>();

            for ( MappedProperty property : method.getMappedProperties() ) {
                Method propertyMappingMethod = getPropertyMappingMethod( methods, property );
                Method reversePropertyMappingMethod = getReversePropertyMappingMethod( methods, property );
                Conversion conversion = conversions.getConversion( property.getSourceType(), property.getTargetType() );

                reportErrorIfPropertyCanNotBeMapped(
                    method,
                    rawReverseMappingMethod,
                    property,
                    propertyMappingMethod,
                    reversePropertyMappingMethod,
                    conversion
                );

                propertyMappings.add(
                    new PropertyMapping(
                        property.getSourceReadAccessorName(),
                        property.getSourceWriteAccessorName(),
                        property.getSourceType(),
                        property.getTargetReadAccessorName(),
                        property.getTargetWriteAccessorName(),
                        property.getTargetType(),
                        propertyMappingMethod != null ? new MappingMethod(
                            propertyMappingMethod.getDeclaringMapper(),
                            propertyMappingMethod.getName(),
                            propertyMappingMethod.getParameterName()
                        ) : null,
                        reversePropertyMappingMethod != null ? new MappingMethod(
                            reversePropertyMappingMethod.getDeclaringMapper(),
                            reversePropertyMappingMethod.getName(),
                            reversePropertyMappingMethod.getParameterName()
                        ) : null,
                        conversion != null ? conversion.to(
                            mappingMethod.getParameterName() + "." + property.getSourceReadAccessorName() + "()",
                            property.getTargetType()
                        ) : null,
                        conversion != null && reverseMappingMethod != null ? conversion.from(
                            reverseMappingMethod.getParameterName() + "." + property.getTargetReadAccessorName() + "()",
                            property.getSourceType()
                        ) : null
                    )
                );
            }

            boolean isIterableMapping = method.getSourceType().isIterableType() && method.getTargetType()
                .isIterableType();

            String toConversionString = null;
            String fromConversionString = null;

            if ( isIterableMapping ) {
                toConversionString = getIterableConversionString(
                    conversions,
                    method.getSourceType().getElementType(),
                    method.getTargetType().getElementType(),
                    true
                );
                fromConversionString = getIterableConversionString(
                    conversions,
                    method.getTargetType().getElementType(),
                    method.getSourceType().getElementType(),
                    false
                );
            }

            BeanMapping mapping = new BeanMapping(
                method.getSourceType(),
                method.getTargetType(),
                propertyMappings,
                mappingMethod,
                reverseMappingMethod,
                toConversionString,
                fromConversionString
            );

            mappings.add( mapping );
        }
        return mappings;
    }

    private void reportErrorIfPropertyCanNotBeMapped(Method method, Method reverseMethod, MappedProperty property,
                                                     Method propertyMappingMethod, Method reversePropertyMappingMethod,
                                                     Conversion conversion) {
        if ( property.getSourceType().equals( property.getTargetType() ) ) {
            return;
        }

        //no mapping method nor conversion nor collection with default implementation
        if ( !(
            propertyMappingMethod != null ||
                conversion != null ||
                ( property.getTargetType().isCollectionType() && property.getTargetType()
                    .getCollectionImplementationType() != null ) ) ) {

            reportError(
                String.format(
                    "Can't map property \"%s %s\" to \"%s %s\".",
                    property.getSourceType(),
                    property.getSourceName(),
                    property.getTargetType(),
                    property.getTargetName()
                ),
                method.getExecutable()
            );
        }

        if ( reverseMethod == null ) {
            return;
        }

        if ( !(
            reversePropertyMappingMethod != null ||
                conversion != null ||
                ( property.getSourceType().isCollectionType() && property.getSourceType()
                    .getCollectionImplementationType() != null ) ) ) {

            reportError(
                String.format(
                    "Can't map property \"%s %s\" to \"%s %s\".",
                    property.getTargetType(),
                    property.getTargetName(),
                    property.getSourceType(),
                    property.getSourceName()
                ),
                reverseMethod.getExecutable()
            );
        }
    }

    private String getIterableConversionString(Conversions conversions, Type sourceElementType, Type targetElementType,
                                               boolean isToConversion) {
        Conversion conversion = conversions.getConversion( sourceElementType, targetElementType );

        if ( conversion == null ) {
            return null;
        }

        return conversion.to(
            Introspector.decapitalize( sourceElementType.getName() ),
            targetElementType
        );
    }

    private List<Type> getUsedMapperTypes(TypeElement element) {
        List<Type> usedMapperTypes = new LinkedList<Type>();
        MapperPrism mapperPrism = MapperPrism.getInstanceOn( element );
        for ( TypeMirror usedMapper : mapperPrism.uses() ) {
            usedMapperTypes.add( typeUtil.retrieveType( usedMapper ) );
        }
        return usedMapperTypes;
    }

    private MappingMethod getElementMappingMethod(Iterable<Method> methods, Method method) {
        Method elementMappingMethod = null;
        for ( Method oneMethod : methods ) {
            if ( oneMethod.getSourceType().equals( method.getSourceType().getElementType() ) ) {
                elementMappingMethod = oneMethod;
                break;
            }
        }
        return elementMappingMethod == null ? null : new MappingMethod(
            elementMappingMethod.getDeclaringMapper(),
            elementMappingMethod.getName(),
            elementMappingMethod.getParameterName()
        );
    }

    private Method getPropertyMappingMethod(Iterable<Method> rawMethods, MappedProperty property) {
        for ( Method oneMethod : rawMethods ) {
            if ( oneMethod.getSourceType().equals( property.getSourceType() ) && oneMethod.getTargetType()
                .equals( property.getTargetType() ) ) {
                return oneMethod;
            }
        }
        return null;
    }

    private Method getReversePropertyMappingMethod(Iterable<Method> methods, MappedProperty property) {
        for ( Method method : methods ) {
            if ( method.getSourceType().equals( property.getTargetType() ) && method.getTargetType()
                .equals( property.getSourceType() ) ) {
                return method;
            }
        }
        return null;
    }

    private Method getReverseMappingMethod(List<Method> rawMethods,
                                           Method method) {
        for ( Method oneMethod : rawMethods ) {
            if ( oneMethod.reverses( method ) ) {
                return oneMethod;
            }
        }
        return null;
    }

    private List<Method> retrieveMethods(Type declaringMapper, Element element) {
        List<Method> methods = new ArrayList<Method>();

        for ( ExecutableElement method : methodsIn( element.getEnclosedElements() ) ) {
            Parameter parameter = retrieveParameter( method );
            Type returnType = retrieveReturnType( method );
            boolean mappingErroneous = false;

            if ( declaringMapper == null ) {
                if ( parameter.getType().isIterableType() && !returnType.isIterableType() ) {
                    reportError( "Can't generate mapping method from iterable type to non-iterable type.", method );
                    mappingErroneous = true;
                }
                if ( !parameter.getType().isIterableType() && returnType.isIterableType() ) {
                    reportError( "Can't generate mapping method from non-iterable type to iterable type.", method );
                    mappingErroneous = true;
                }
                if ( parameter.getType().isPrimitive() ) {
                    reportError( "Can't generate mapping method with primitive parameter type.", method );
                    mappingErroneous = true;
                }
                if ( returnType.isPrimitive() ) {
                    reportError( "Can't generate mapping method with primitive return type.", method );
                    mappingErroneous = true;
                }

                if ( mappingErroneous ) {
                    continue;
                }
            }

            //retrieve property mappings if an implementation for the method needs to be generated
            List<MappedProperty> properties = declaringMapper == null ? retrieveMappedProperties( method ) : Collections
                .<MappedProperty>emptyList();

            methods.add(
                new Method(
                    declaringMapper,
                    method,
                    parameter.getName(),
                    parameter.getType(),
                    returnType,
                    properties
                )
            );
        }

        MapperPrism mapperPrism = MapperPrism.getInstanceOn( element );

        if ( mapperPrism != null ) {
            for ( TypeMirror usedMapper : mapperPrism.uses() ) {
                methods.addAll(
                    retrieveMethods(
                        typeUtil.retrieveType( usedMapper ),
                        ( (DeclaredType) usedMapper ).asElement()
                    )
                );
            }
        }

        return methods;
    }

    /**
     * Returns all properties of the parameter type of the given method which
     * are mapped to a corresponding property of the return type of the given
     * method.
     *
     * @param method The method of interest
     *
     * @return All mapped properties for the given method
     */
    private List<MappedProperty> retrieveMappedProperties(ExecutableElement method) {
        Map<String, Mapping> mappings = getMappings( method );

        TypeElement returnTypeElement = (TypeElement) typeUtils.asElement( method.getReturnType() );
        TypeElement parameterElement = (TypeElement) typeUtils.asElement( method.getParameters().get( 0 ).asType() );

        List<MappedProperty> properties = new ArrayList<MappedProperty>();

        List<ExecutableElement> sourceGetters = Filters.getterMethodsIn(
            elementUtils.getAllMembers( parameterElement )
        );
        List<ExecutableElement> targetSetters = Filters.setterMethodsIn(
            elementUtils.getAllMembers( returnTypeElement )
        );
        List<ExecutableElement> sourceSetters = Filters.setterMethodsIn(
            elementUtils.getAllMembers( parameterElement )
        );
        List<ExecutableElement> targetGetters = Filters.getterMethodsIn(
            elementUtils.getAllMembers( returnTypeElement )
        );

        reportErrorIfMappedPropertiesDontExist( method, mappings, sourceGetters, targetSetters );

        for ( ExecutableElement getterMethod : sourceGetters ) {
            String sourcePropertyName = Executables.getPropertyName( getterMethod );
            Mapping mapping = mappings.get( sourcePropertyName );

            for ( ExecutableElement setterMethod : targetSetters ) {
                String targetPropertyName = Executables.getPropertyName( setterMethod );

                if ( targetPropertyName.equals( mapping != null ? mapping.getTargetName() : sourcePropertyName ) ) {
                    ExecutableElement correspondingSetter = Executables.getCorrespondingPropertyAccessor(
                        getterMethod,
                        sourceSetters
                    );
                    ExecutableElement correspondingGetter = Executables.getCorrespondingPropertyAccessor(
                        setterMethod,
                        targetGetters
                    );
                    properties.add(
                        new MappedProperty(
                            sourcePropertyName,
                            getterMethod.getSimpleName().toString(),
                            correspondingSetter != null ? correspondingSetter.getSimpleName().toString() : null,
                            retrieveReturnType( getterMethod ),
                            mapping != null ? mapping.getTargetName() : targetPropertyName,
                            correspondingGetter != null ? correspondingGetter.getSimpleName().toString() : null,
                            setterMethod.getSimpleName().toString(),
                            retrieveParameter( setterMethod ).getType()
                        )
                    );
                }
            }
        }

        return properties;
    }

    private void reportErrorIfMappedPropertiesDontExist(ExecutableElement method, Map<String, Mapping> mappings,
                                                        List<ExecutableElement> sourceGetters,
                                                        List<ExecutableElement> targetSetters) {

        Set<String> sourcePropertyNames = Executables.getPropertyNames( sourceGetters );
        Set<String> targetPropertyNames = Executables.getPropertyNames( targetSetters );

        for ( Mapping mappedProperty : mappings.values() ) {
            if ( !sourcePropertyNames.contains( mappedProperty.getSourceName() ) ) {
                reportError(
                    String.format(
                        "Unknown property \"%s\" in parameter type %s.",
                        mappedProperty.getSourceName(),
                        retrieveParameter( method ).getType()
                    ), method, mappedProperty.getMirror(), mappedProperty.getSourceAnnotationValue()
                );
            }
            if ( !targetPropertyNames.contains( mappedProperty.getTargetName() ) ) {
                reportError(
                    String.format(
                        "Unknown property \"%s\" in return type %s.",
                        mappedProperty.getTargetName(),
                        retrieveReturnType( method )
                    ), method, mappedProperty.getMirror(), mappedProperty.getTargetAnnotationValue()
                );
            }
        }
    }

    /**
     * Retrieves the mappings configured via {@code @Mapping} from the given
     * method.
     *
     * @param method The method of interest
     *
     * @return The mappings for the given method, keyed by source property name
     */
    private Map<String, Mapping> getMappings(ExecutableElement method) {
        Map<String, Mapping> mappings = new HashMap<String, Mapping>();

        MappingPrism mappingAnnotation = MappingPrism.getInstanceOn( method );
        MappingsPrism mappingsAnnotation = MappingsPrism.getInstanceOn( method );

        if ( mappingAnnotation != null ) {
            mappings.put( mappingAnnotation.source(), Mapping.fromMappingPrism( mappingAnnotation ) );
        }

        if ( mappingsAnnotation != null ) {
            mappings.putAll( Mapping.fromMappingsPrism( mappingsAnnotation ) );
        }

        return mappings;
    }

    private Parameter retrieveParameter(ExecutableElement method) {
        List<? extends VariableElement> parameters = method.getParameters();

        if ( parameters.size() != 1 ) {
            //TODO: Log error
            return null;
        }

        VariableElement parameter = parameters.get( 0 );

        return new Parameter(
            parameter.getSimpleName().toString(),
            typeUtil.retrieveType( parameter.asType() )
        );
    }

    private Type retrieveReturnType(ExecutableElement method) {
        return typeUtil.retrieveType( method.getReturnType() );
    }

    private void reportError(String message, Element element) {
        processingEnvironment.getMessager().printMessage( Kind.ERROR, message, element );
        mappingErroneous = true;
    }

    private void reportError(String message, Element element, AnnotationMirror annotationMirror,
                             AnnotationValue annotationValue) {
        processingEnvironment.getMessager()
            .printMessage( Kind.ERROR, message, element, annotationMirror, annotationValue );
        mappingErroneous = true;
    }
}
