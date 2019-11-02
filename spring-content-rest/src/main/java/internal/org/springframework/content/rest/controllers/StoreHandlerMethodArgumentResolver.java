package internal.org.springframework.content.rest.controllers;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;

import javax.persistence.Embeddable;
import javax.servlet.http.HttpServletRequest;

import internal.org.springframework.content.rest.utils.ContentStoreUtils;
import internal.org.springframework.content.rest.utils.PersistentEntityUtils;
import internal.org.springframework.content.rest.utils.RepositoryUtils;

import org.springframework.content.commons.annotations.ContentId;
import org.springframework.content.commons.repository.AssociativeStore;
import org.springframework.content.commons.repository.Store;
import org.springframework.content.commons.storeservice.ContentStoreInfo;
import org.springframework.content.commons.storeservice.ContentStoreService;
import org.springframework.content.commons.utils.BeanUtils;
import org.springframework.content.rest.config.RestConfiguration;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.repository.support.RepositoryInvoker;
import org.springframework.data.repository.support.RepositoryInvokerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.MethodNotAllowedException;

public class StoreHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

    private final RestConfiguration config;
    private final Repositories repositories;
    private final RepositoryInvokerFactory repoInvokerFactory;
    private final ContentStoreService stores;

    public StoreHandlerMethodArgumentResolver(RestConfiguration config, Repositories repositories, RepositoryInvokerFactory repoInvokerFactory, ContentStoreService stores) {
        this.config = config;
        this.repositories = repositories;
        this.repoInvokerFactory = repoInvokerFactory;
        this.stores = stores;
    }

    RestConfiguration getConfig() {
        return config;
    }

    protected Repositories getRepositories() {
        return repositories;
    }

    RepositoryInvokerFactory getRepoInvokerFactory() {
        return repoInvokerFactory;
    }

    protected ContentStoreService getStores() {
        return stores;
    }

    public boolean supportsParameter(MethodParameter parameter) {
        return Store.class.isAssignableFrom(parameter.getParameterType());
    }

    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {

        String pathInfo = webRequest.getNativeRequest(HttpServletRequest.class).getRequestURI();

        String[] pathSegments = pathInfo.split("/");
        if (pathSegments.length < 2) {
            return null;
        }

        String store = pathSegments[1];

        ContentStoreInfo info = ContentStoreUtils.findStore(stores, store);
        if (info == null) {
            throw new IllegalArgumentException(String.format("Store for path %s not found", store));
        }

        if (AssociativeStore.class.isAssignableFrom(info.getInterface())) {
            // do associativestore resource resolution

            // entity content
            if (pathSegments.length > 3) {
                String id = pathSegments[2];

                Object domainObj = findOne(repoInvokerFactory, repositories, info.getDomainObjectClass(), id);

                Object contentId = BeanUtils.getFieldWithAnnotation(domainObj, ContentId.class);
                if (contentId == null) {
                    throw new ResourceNotFoundException();
                }

                return info.getImplementation(AssociativeStore.class);

                // property content
            }

            // do store resource resolution
        } else if (Store.class.isAssignableFrom(info.getInterface())) {

            return info.getImplementation(Store.class);
        }

        throw new IllegalArgumentException();
    }

    protected <T> Object resolveProperty(HttpMethod method, Repositories repositories, ContentStoreService stores, String[] segments, PropertyResolver<T> resolver) {

        String repository = segments[1];
        String id = segments[2];
        String contentProperty = segments[3];
        String contentPropertyId = null;
        if (segments.length == 5) {
            contentPropertyId = segments[4];
        }

        Object domainObj = null;
        try {
            domainObj = findOne(repoInvokerFactory, repositories, repository, id);
        }
        catch (HttpRequestMethodNotSupportedException e) {
            throw new ResourceNotFoundException();
        }

        PersistentEntity<?, ?> entity = repositories.getPersistentEntity(domainObj.getClass());
        if (null == entity) {
            throw new ResourceNotFoundException();
        }

        PersistentProperty<?> property = getContentPropertyDefinition(entity, contentProperty);

        // get property class
        Class<?> propertyClass = null;
        if (!PersistentEntityUtils.isPropertyMultiValued(property)) {
            propertyClass = property.getActualType();
        }
        // null multi-valued content property
        else if (PersistentEntityUtils.isPropertyMultiValued(property)) {
            propertyClass = property.getActualType();
        }

        // get property store
        ContentStoreInfo info = ContentStoreUtils.findContentStore(stores, propertyClass);
        if (info == null) {
            throw new IllegalStateException(String.format("Store for property %s not found", property.getName()));
        }

        // get or create property value
        PersistentPropertyAccessor accessor = property.getOwner().getPropertyAccessor(domainObj);
        Object propVal = accessor.getProperty(property);
        if (propVal == null) {
            // null single-valued property
            if (!PersistentEntityUtils.isPropertyMultiValued(property)) {
                propVal = instantiate(propertyClass);
                accessor.setProperty(property, propVal);
            }
            // null multi-valued content property
            else {
                if (property.isArray()) {
                    Object member = instantiate(propertyClass);
                    try {
                        member = StoreRestController.save(repositories, member);
                    }
                    catch (HttpRequestMethodNotSupportedException e) {
                        e.printStackTrace();
                    }

                    Object newArray = Array.newInstance(propertyClass, 1);
                    Array.set(newArray, 0, member);
                    accessor.setProperty(property, newArray);
                    propVal = member;
                }
                else if (property.isCollectionLike()) {
                    Object member = instantiate(propertyClass);
                    @SuppressWarnings("unchecked")
                    Collection<Object> contentCollection = (Collection<Object>) accessor.getProperty(property);
                    contentCollection.add(member);
                    propVal = member;
                }
            }
        } else {
            if (contentPropertyId != null) {
                if (property.isArray()) {
                    Object componentValue = null;
                    for (Object content : (Object[])propVal) {
                        if (BeanUtils.hasFieldWithAnnotation(content, ContentId.class) && BeanUtils.getFieldWithAnnotation(content, ContentId.class) != null) {
                            String candidateId = BeanUtils.getFieldWithAnnotation(content, ContentId.class).toString();
                            if (candidateId.equals(contentPropertyId)) {
                                componentValue = content;
                                break;
                            }
                        }
                    }
                    propVal = componentValue;
                } else if (property.isCollectionLike()) {
                    Object componentValue = null;
                    for (Object content : (Collection<?>)propVal) {
                        if (BeanUtils.hasFieldWithAnnotation(content, ContentId.class) && BeanUtils.getFieldWithAnnotation(content, ContentId.class) != null) {
                            String candidateId = BeanUtils.getFieldWithAnnotation(content, ContentId.class).toString();
                            if (candidateId.equals(contentPropertyId)) {
                                componentValue = content;
                                break;
                            }
                        }
                    }
                    propVal = componentValue;
                }
            } else if (contentPropertyId == null &&
                    (PersistentEntityUtils.isPropertyMultiValued(property) &&
                            (method.equals(HttpMethod.GET) || method.equals(HttpMethod.DELETE)))) {
                throw new MethodNotAllowedException("GET", null);
            } else if (contentPropertyId == null) {
                if (property.isArray()) {
                    Object member = instantiate(propertyClass);
                    Object newArray = Array.newInstance(propertyClass,Array.getLength(propVal) + 1);
                    System.arraycopy(propVal, 0, newArray, 0, Array.getLength(propVal));
                    Array.set(newArray, Array.getLength(propVal), member);
                    accessor.setProperty(property, newArray);
                    propVal = member;
                } else if (property.isCollectionLike()) {
                    Object member = instantiate(propertyClass);
                    @SuppressWarnings("unchecked")
                    Collection<Object> contentCollection = (Collection<Object>) accessor.getProperty(property);
                    contentCollection.add(member);
                    propVal = member;
                }
            }
        }

        boolean embeddedProperty = false;
        if (PersistentEntityUtils.isPropertyMultiValued(property) || propVal.getClass().getAnnotation(Embeddable.class) != null) {
            embeddedProperty = true;
        }

        return resolver.resolve(info, domainObj, propVal, embeddedProperty);
    }

    private PersistentProperty<?> getContentPropertyDefinition(PersistentEntity<?, ?> persistentEntity, String contentProperty) {
        PersistentProperty<?> prop = persistentEntity.getPersistentProperty(contentProperty);

        if (null == prop) {
            throw new ResourceNotFoundException();
        }

        return prop;
    }

    private Object findOne(RepositoryInvokerFactory repoInvokerFactory, Repositories repositories, String repository, String id)
            throws HttpRequestMethodNotSupportedException {

        RepositoryInformation ri = RepositoryUtils.findRepositoryInformation(repositories, repository);

        if (ri == null) {
            throw new ResourceNotFoundException();
        }

        Class<?> domainObjClazz = ri.getDomainType();

        return findOne(repoInvokerFactory, repositories, domainObjClazz, id);
    }

    protected Object findOne(RepositoryInvokerFactory repoInvokerFactory, Repositories repositories, Class<?> domainObjClass, String id)
            throws HttpRequestMethodNotSupportedException {

        Optional<Object> domainObj = null;

        if (repoInvokerFactory != null) {

            RepositoryInvoker invoker = repoInvokerFactory.getInvokerFor(domainObjClass);
            if (invoker != null) {
                domainObj = invoker.invokeFindById(id);
            }
        } else {

            RepositoryInformation ri = RepositoryUtils.findRepositoryInformation(repositories, domainObjClass);

            if (ri == null) {
                throw new ResourceNotFoundException();
            }

            Class<?> domainObjClazz = ri.getDomainType();
            Class<?> idClazz = ri.getIdType();

            Optional<Method> findOneMethod = ri.getCrudMethods().getFindOneMethod();
            if (!findOneMethod.isPresent()) {
                throw new HttpRequestMethodNotSupportedException("fineOne");
            }

            Object oid = new DefaultConversionService().convert(id, idClazz);
            domainObj = (Optional<Object>) ReflectionUtils.invokeMethod(findOneMethod.get(),
                    repositories.getRepositoryFor(domainObjClazz).get(),
                    oid);
        }
        return domainObj.orElseThrow(ResourceNotFoundException::new);
    }

    private Object instantiate(Class<?> clazz) {
        Object newObject = null;
        try {
            newObject = clazz.newInstance();
        }
        catch (InstantiationException e) {
            e.printStackTrace();
        }
        catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return newObject;
    }

    @FunctionalInterface
    public interface PropertyResolver<S> {
        S resolve(ContentStoreInfo store, Object parent, Object property, boolean embedded);
    }
}