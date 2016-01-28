/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.ResourceProcessor;
import org.springframework.hateoas.ResourceSupport;
import org.springframework.hateoas.Resources;
import org.springframework.hateoas.core.EmbeddedWrapper;
import org.springframework.hateoas.mvc.HeaderLinksResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link HandlerMethodReturnValueHandler} to post-process the objects returned from controller methods using the
 * configured {@link ResourceProcessor}s.
 * 
 * @author Oliver Gierke
 */
public class ResourceProcessorHandlerMethodReturnValueHandler implements HandlerMethodReturnValueHandler {

	private static final ResolvableType RESOURCE_TYPE = ResolvableType.forClass(Resource.class);
	private static final ResolvableType RESOURCES_TYPE = ResolvableType.forClass(Resources.class);
	private static final ResolvableType HTTP_ENTITY_TYPE = ResolvableType.forClass(HttpEntity.class);

	private static final Field CONTENT_FIELD = ReflectionUtils.findField(Resources.class, "content");

	static {
		ReflectionUtils.makeAccessible(CONTENT_FIELD);
	}

	private final HandlerMethodReturnValueHandler delegate;
	private final List<ProcessorWrapper> processors;

	private boolean rootLinksAsHeaders = false;

	/**
	 * Creates a new {@link ResourceProcessorHandlerMethodReturnValueHandler} using the given delegate to eventually
	 * delegate calls to {@link #handleReturnValue(Object, MethodParameter, ModelAndViewContainer, NativeWebRequest)} to.
	 * Will consider the given {@link ResourceProcessor} to post-process the controller methods return value to before
	 * invoking the delegate.
	 * 
	 * @param delegate the {@link HandlerMethodReturnValueHandler} to evenually delegate calls to, must not be
	 *          {@literal null}.
	 * @param processors the {@link ResourceProcessor}s to be considered, must not be {@literal null}.
	 */
	public ResourceProcessorHandlerMethodReturnValueHandler(HandlerMethodReturnValueHandler delegate,
			List<ResourceProcessor<?>> processors) {

		Assert.notNull(delegate, "Delegate must not be null!");
		Assert.notNull(processors, "ResourceProcessors must not be null!");

		this.delegate = delegate;
		this.processors = new ArrayList<ProcessorWrapper>();

		for (ResourceProcessor<?> processor : processors) {

			ResolvableType processorType = ResolvableType.forClass(ResourceProcessor.class, processor.getClass());
			Class<?> rawType = processorType.getGeneric(0).resolve();

			if (Resource.class.isAssignableFrom(rawType)) {
				this.processors.add(new ResourceProcessorWrapper(processor));
			} else if (Resources.class.isAssignableFrom(rawType)) {
				this.processors.add(new ResourcesProcessorWrapper(processor));
			} else {
				this.processors.add(new DefaultProcessorWrapper(processor));
			}
		}

		Collections.sort(this.processors, AnnotationAwareOrderComparator.INSTANCE);
	}

	/**
	 * @param rootLinksAsHeaders the rootLinksAsHeaders to set
	 */
	public void setRootLinksAsHeaders(boolean rootLinksAsHeaders) {
		this.rootLinksAsHeaders = rootLinksAsHeaders;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.method.support.HandlerMethodReturnValueHandler#supportsReturnType(org.springframework.core.MethodParameter)
	 */
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return delegate.supportsReturnType(returnType);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.web.method.support.HandlerMethodReturnValueHandler#handleReturnValue(java.lang.Object, org.springframework.core.MethodParameter, org.springframework.web.method.support.ModelAndViewContainer, org.springframework.web.context.request.NativeWebRequest)
	 */
	@Override
	public void handleReturnValue(Object returnValue, MethodParameter returnType, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest) throws Exception {

		Object value = returnValue;

		if (returnValue instanceof HttpEntity) {
			value = ((HttpEntity<?>) returnValue).getBody();
		}

		// No post-processable type found - proceed with delegate
		if (!ResourceSupport.class.isInstance(value)) {
			delegate.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
			return;
		}

		// We have a Resource or Resources - find suitable processors
		ResolvableType targetType = ResolvableType.forMethodReturnType(returnType.getMethod());

		// Unbox HttpEntity
		if (HTTP_ENTITY_TYPE.isAssignableFrom(targetType)) {
			targetType = targetType.getGeneric(0);
		}

		ResolvableType returnValueType = ResolvableType.forClass(value.getClass());

		// Returned value is actually of a more specific type, use this type information
		if (!getRawType(targetType).equals(getRawType(returnValueType))) {
			targetType = returnValueType;
		}

		// For Resources implementations, process elements first
		if (RESOURCES_TYPE.isAssignableFrom(targetType)) {

			Resources<?> resources = (Resources<?>) value;
			ResolvableType elementTargetType = ResolvableType.forClass(Resources.class, targetType.getRawClass())
					.getGeneric(0);
			List<Object> result = new ArrayList<Object>(resources.getContent().size());

			for (Object element : resources) {

				ResolvableType elementType = ResolvableType.forClass(element.getClass());

				if (!getRawType(elementTargetType).equals(elementType.getRawClass())) {
					elementTargetType = elementType;
				}

				result.add(invokeProcessorsFor(element, elementTargetType));
			}

			ReflectionUtils.setField(CONTENT_FIELD, resources, result);
		}

		ResourceSupport result = (ResourceSupport) invokeProcessorsFor(value, targetType);
		delegate.handleReturnValue(rewrapResult(result, returnValue), returnType, mavContainer, webRequest);
	}

	/**
	 * Invokes all registered {@link ResourceProcessor}s registered for the given {@link ResolvableType}.
	 * 
	 * @param value the object to process
	 * @param type
	 * @return
	 */
	private Object invokeProcessorsFor(Object value, ResolvableType type) {

		Object currentValue = value;

		// Process actual value
		for (ProcessorWrapper wrapper : this.processors) {
			if (wrapper.supports(type, currentValue)) {
				currentValue = wrapper.invokeProcessor(currentValue);
			}
		}

		return currentValue;
	}

	/**
	 * Re-wraps the result of the post-processing work into an {@link HttpEntity} or {@link ResponseEntity} if the
	 * original value was one of those two types. Copies headers and status code from the original value but uses the new
	 * body.
	 * 
	 * @param newBody the post-processed value.
	 * @param originalValue the original input value.
	 * @return
	 */
	Object rewrapResult(ResourceSupport newBody, Object originalValue) {

		if (!(originalValue instanceof HttpEntity)) {
			return newBody;
		}

		HttpEntity<ResourceSupport> entity = null;

		if (originalValue instanceof ResponseEntity) {
			ResponseEntity<?> source = (ResponseEntity<?>) originalValue;
			entity = new ResponseEntity<ResourceSupport>(newBody, source.getHeaders(), source.getStatusCode());
		} else {
			HttpEntity<?> source = (HttpEntity<?>) originalValue;
			entity = new HttpEntity<ResourceSupport>(newBody, source.getHeaders());
		}

		return addLinksToHeaderWrapper(entity);
	}

	private HttpEntity<?> addLinksToHeaderWrapper(HttpEntity<ResourceSupport> entity) {
		return rootLinksAsHeaders ? HeaderLinksResponseEntity.wrap(entity) : entity;
	}

	private static boolean isRawTypeAssignable(ResolvableType left, Class<?> right) {
		return getRawType(left).isAssignableFrom(right);
	}

	private static Class<?> getRawType(ResolvableType type) {

		Class<?> rawType = type.getRawClass();
		return rawType == null ? Object.class : rawType;
	}

	private static ResolvableType findGenericType(ResolvableType source, Class<?> type) {

		Class<?> rawType = getRawType(source);

		if (Object.class.equals(rawType)) {
			return null;
		}

		if (rawType.equals(type)) {
			return source;
		}

		return findGenericType(source.getSuperType(), type);
	}

	/**
	 * Interface to unify interaction with {@link ResourceProcessor}s. The {@link Ordered} rank should be determined by
	 * the underlying processor.
	 * 
	 * @author Oliver Gierke
	 */
	private interface ProcessorWrapper extends Ordered {

		/**
		 * Returns whether the underlying processor supports the given {@link ResolvableType}. It might also additionally
		 * inspect the object that would eventually be handed to the processor.
		 * 
		 * @param type the type of object to be post processed, will never be {@literal null}.
		 * @param value the object that would be passed into the processor eventually, can be {@literal null}.
		 * @return
		 */
		boolean supports(ResolvableType type, Object value);

		/**
		 * Performs the actual invocation of the processor. Implementations can be sure
		 * {@link #supports(ResolvableType, Object)} has been called before and returned {@literal true}.
		 * 
		 * @param object
		 */
		Object invokeProcessor(Object object);
	}

	/**
	 * Default implementation of {@link ProcessorWrapper} to generically deal with {@link ResourceSupport} types.
	 * 
	 * @author Oliver Gierke
	 */
	private static class DefaultProcessorWrapper implements ProcessorWrapper {

		private final ResourceProcessor<?> processor;
		private final ResolvableType targetType;

		/**
		 * Creates a new {@link DefaultProcessorWrapper} with the given {@link ResourceProcessor}.
		 * 
		 * @param processor must not be {@literal null}.
		 */
		public DefaultProcessorWrapper(ResourceProcessor<?> processor) {

			Assert.notNull(processor);

			this.processor = processor;
			this.targetType = ResolvableType.forClass(ResourceProcessor.class, processor.getClass()).getGeneric(0);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.rest.webmvc.ResourceProcessorHandlerMethodReturnValueHandler.ProcessorWrapper#supports(org.springframework.core.ResolvableType, java.lang.Object)
		 */
		@Override
		public boolean supports(ResolvableType type, Object value) {
			return isRawTypeAssignable(targetType, getRawType(type));
		}

		/* 
		 * (non-Javadoc)
		 * @see org.springframework.data.rest.webmvc.ResourceProcessorHandlerMethodReturnValueHandler.PostProcessorWrapper#invokeProcessor(java.lang.Object)
		 */
		@Override
		@SuppressWarnings("unchecked")
		public Object invokeProcessor(Object object) {
			return ((ResourceProcessor<ResourceSupport>) processor).process((ResourceSupport) object);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.core.Ordered#getOrder()
		 */
		@Override
		public int getOrder() {
			return CustomOrderAwareComparator.INSTANCE.getOrder(processor);
		}

		/**
		 * Returns the target type the underlying {@link ResourceProcessor} wants to get invoked for.
		 * 
		 * @return the targetType
		 */
		public ResolvableType getTargetType() {
			return targetType;
		}
	}

	/**
	 * {@link ProcessorWrapper} to deal with {@link ResourceProcessor}s for {@link Resource}s. Will fall back to peeking
	 * into the {@link Resource}'s content for type resolution.
	 * 
	 * @author Oliver Gierke
	 */
	private static class ResourceProcessorWrapper extends DefaultProcessorWrapper {

		/**
		 * Creates a new {@link ResourceProcessorWrapper} for the given {@link ResourceProcessor}.
		 * 
		 * @param processor must not be {@literal null}.
		 */
		public ResourceProcessorWrapper(ResourceProcessor<?> processor) {
			super(processor);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.rest.webmvc.ResourceProcessorHandlerMethodReturnValueHandler.DefaultProcessorWrapper#supports(org.springframework.core.ResolvableType, java.lang.Object)
		 */
		@Override
		public boolean supports(ResolvableType type, Object value) {

			if (!RESOURCE_TYPE.isAssignableFrom(type)) {
				return false;
			}

			return super.supports(type, value) && isValueTypeMatch((Resource<?>) value, getTargetType());
		}

		/**
		 * Returns whether the given {@link Resource} matches the given target {@link ResolvableType}. We inspect the
		 * {@link Resource}'s value to determine the match.
		 * 
		 * @param resource
		 * @param target must not be {@literal null}.
		 * @return whether the given {@link Resource} can be assigned to the given target {@link ResolvableType}
		 */
		private static boolean isValueTypeMatch(Resource<?> resource, ResolvableType target) {

			if (resource == null || !isRawTypeAssignable(target, resource.getClass())) {
				return false;
			}

			Object content = resource.getContent();

			if (content == null) {
				return false;
			}

			ResolvableType type = findGenericType(target, Resource.class);
			return type != null && type.getGeneric(0).isAssignableFrom(ResolvableType.forClass(content.getClass()));
		}
	}

	/**
	 * {@link ProcessorWrapper} for {@link ResourceProcessor}s targeting {@link Resources}. Will peek into the content of
	 * the {@link Resources} for type matching decisions if needed.
	 * 
	 * @author Oliver Gierke
	 */
	static class ResourcesProcessorWrapper extends DefaultProcessorWrapper {

		/**
		 * Creates a new {@link ResourcesProcessorWrapper} for the given {@link ResourceProcessor}.
		 * 
		 * @param processor must not be {@literal null}.
		 */
		public ResourcesProcessorWrapper(ResourceProcessor<?> processor) {
			super(processor);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.rest.webmvc.ResourceProcessorHandlerMethodReturnValueHandler.DefaultProcessorWrapper#supports(org.springframework.core.ResolvableType, java.lang.Object)
		 */
		@Override
		public boolean supports(ResolvableType type, Object value) {

			if (!RESOURCES_TYPE.isAssignableFrom(type)) {
				return false;
			}

			return super.supports(type, value) && isValueTypeMatch((Resources<?>) value, getTargetType());
		}

		/**
		 * Returns whether the given {@link Resources} instance matches the given {@link ResolvableType}. We predict this by
		 * inspecting the first element of the content of the {@link Resources}.
		 * 
		 * @param resources the {@link Resources} to inspect.
		 * @param target that target {@link ResolvableType}.
		 * @return
		 */
		static boolean isValueTypeMatch(Resources<?> resources, ResolvableType target) {

			if (resources == null) {
				return false;
			}

			Collection<?> content = resources.getContent();

			if (content.isEmpty()) {
				return false;
			}

			ResolvableType superType = null;

			for (Class<?> resourcesType : Arrays.<Class<?>> asList(resources.getClass(), Resources.class)) {

				superType = ResolvableType.forClass(resourcesType, getRawType(target));

				if (superType != null) {
					break;
				}
			}

			if (superType == null) {
				return false;
			}

			Object element = content.iterator().next();
			ResolvableType resourceType = superType.getGeneric(0);

			if (element instanceof Resource) {
				return ResourceProcessorWrapper.isValueTypeMatch((Resource<?>) element, resourceType);
			} else if (element instanceof EmbeddedWrapper) {
				return isRawTypeAssignable(resourceType, ((EmbeddedWrapper) element).getRelTargetType());
			}

			return false;
		}
	}

	/**
	 * Helper extension of {@link AnnotationAwareOrderComparator} to make {@link #getOrder(Object)} public to allow it
	 * being used in a standalone fashion.
	 * 
	 * @author Oliver Gierke
	 */
	private static class CustomOrderAwareComparator extends AnnotationAwareOrderComparator {

		public static CustomOrderAwareComparator INSTANCE = new CustomOrderAwareComparator();

		@Override
		protected int getOrder(Object obj) {
			return super.getOrder(obj);
		}
	}
}