/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.webbeans;

import java.io.InputStream;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.el.ELResolver;
import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.ScopeType;
import javax.enterprise.context.spi.Context;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.AmbiguousResolutionException;
import javax.enterprise.inject.BindingType;
import javax.enterprise.inject.Stereotype;
import javax.enterprise.inject.TypeLiteral;
import javax.enterprise.inject.UnproxyableResolutionException;
import javax.enterprise.inject.UnsatisfiedResolutionException;
import javax.enterprise.inject.deployment.Production;
import javax.enterprise.inject.deployment.Standard;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Decorator;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InterceptionType;
import javax.enterprise.inject.spi.Interceptor;
import javax.enterprise.inject.spi.ManagedBean;
import javax.enterprise.inject.spi.ObserverMethod;
import javax.event.Observer;
import javax.inject.DeploymentException;
import javax.inject.DuplicateBindingTypeException;
import javax.interceptor.InterceptorBindingType;

import org.jboss.webbeans.bean.DisposalMethodBean;
import org.jboss.webbeans.bean.EnterpriseBean;
import org.jboss.webbeans.bean.NewEnterpriseBean;
import org.jboss.webbeans.bean.RIBean;
import org.jboss.webbeans.bean.proxy.ClientProxyProvider;
import org.jboss.webbeans.bootstrap.api.ServiceRegistry;
import org.jboss.webbeans.context.ApplicationContext;
import org.jboss.webbeans.context.CreationalContextImpl;
import org.jboss.webbeans.el.Namespace;
import org.jboss.webbeans.el.WebBeansELResolver;
import org.jboss.webbeans.event.EventManager;
import org.jboss.webbeans.event.EventObserver;
import org.jboss.webbeans.event.ObserverImpl;
import org.jboss.webbeans.injection.NonContextualInjector;
import org.jboss.webbeans.injection.resolution.ResolvableAnnotatedClass;
import org.jboss.webbeans.injection.resolution.Resolver;
import org.jboss.webbeans.introspector.AnnotatedItem;
import org.jboss.webbeans.log.Log;
import org.jboss.webbeans.log.Logging;
import org.jboss.webbeans.manager.api.WebBeansManager;
import org.jboss.webbeans.metadata.MetaDataCache;
import org.jboss.webbeans.metadata.StereotypeModel;
import org.jboss.webbeans.util.Beans;
import org.jboss.webbeans.util.Proxies;
import org.jboss.webbeans.util.Reflections;
import org.jboss.webbeans.util.collections.multi.ConcurrentListHashMultiMap;
import org.jboss.webbeans.util.collections.multi.ConcurrentListMultiMap;
import org.jboss.webbeans.util.collections.multi.ConcurrentSetHashMultiMap;
import org.jboss.webbeans.util.collections.multi.ConcurrentSetMultiMap;

/**
 * Implementation of the Web Beans Manager.
 * 
 * Essentially a singleton for registering Beans, Contexts, Observers,
 * Interceptors etc. as well as providing resolution
 * 
 * @author Pete Muir
 * 
 */
public class ManagerImpl implements WebBeansManager, Serializable
{
   
   private static class CurrentActivity
   {
      
      private final Context context;
      private final ManagerImpl manager;      
      
      public CurrentActivity(Context context, ManagerImpl manager)
      {
         this.context = context;
         this.manager = manager;
      }

      public Context getContext()
      {
         return context;
      }
      
      public ManagerImpl getManager()
      {
         return manager;
      }
      
      @Override
      public boolean equals(Object obj)
      {
         if (obj instanceof CurrentActivity)
         {
            return this.getContext().equals(((CurrentActivity) obj).getContext());
         }
         else
         {
            return false;
         }
      }
      
      @Override
      public int hashCode()
      {
         return getContext().hashCode();
      }
      
      @Override
      public String toString()
      {
         return getContext() + " -> " + getManager();
      }
   }
   
   private static final Log log = Logging.getLog(ManagerImpl.class);

   private static final long serialVersionUID = 3021562879133838561L;

   // The JNDI key to place the manager under
   public static final String JNDI_KEY = "java:app/Manager";
   
   /*
    * Application scoped services
    * ****************************
    */  
   private transient final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
   private transient final ServiceRegistry services;
   
   /*
    * Application scoped data structures
    * ***********************************
    */
   private transient List<Class<? extends Annotation>> enabledDeploymentTypes;
   private transient final ConcurrentListMultiMap<Class<? extends Annotation>, Context> contexts;
   private final transient Set<CurrentActivity> currentActivities;
   private transient final ClientProxyProvider clientProxyProvider;
   private transient final Map<Class<?>, EnterpriseBean<?>> newEnterpriseBeans;
   private transient final Map<String, RIBean<?>> riBeans;
   private final transient Map<Bean<?>, Bean<?>> specializedBeans;
   private final transient AtomicInteger ids;
   
   /*
    * Activity scoped services
    * *************************
    */  
   private transient final EventManager eventManager;
   private transient final Resolver resolver;
   private final transient NonContextualInjector nonContextualInjector;   
   
   /*
    * Activity scoped data structures
    * ********************************
    */
   private transient final ThreadLocal<Stack<InjectionPoint>> currentInjectionPoint;
   private transient List<Bean<?>> beanWithManagers;
   private final transient Namespace rootNamespace;
   private final transient ConcurrentSetMultiMap<Type, EventObserver<?>> registeredObservers;
   private final transient Set<ManagerImpl> childActivities;
   private final Integer id;
   
   
   /**
    * Create a new, root, manager
    * 
    * @param serviceRegistry
    * @return
    */
   public static ManagerImpl newRootManager(ServiceRegistry serviceRegistry)
   {
      List<Class<? extends Annotation>> defaultEnabledDeploymentTypes = new ArrayList<Class<? extends Annotation>>();
      defaultEnabledDeploymentTypes.add(0, Standard.class);
      defaultEnabledDeploymentTypes.add(1, Production.class);
      
      return new ManagerImpl(
            serviceRegistry, 
            new CopyOnWriteArrayList<Bean<?>>(), 
            new ConcurrentSetHashMultiMap<Type, EventObserver<?>>(),
            new Namespace(),
            new ConcurrentHashMap<Class<?>, EnterpriseBean<?>>(),
            new ConcurrentHashMap<String, RIBean<?>>(), 
            new ClientProxyProvider(), 
            new ConcurrentListHashMultiMap<Class<? extends Annotation>, Context>(),
            new CopyOnWriteArraySet<CurrentActivity>(),
            new HashMap<Bean<?>, Bean<?>>(),
            defaultEnabledDeploymentTypes,
            new AtomicInteger()
            );
   }
   
   /**
    * Create a new child manager
    * 
    * @param parentManager
    * @return
    */
   public static ManagerImpl newChildManager(ManagerImpl parentManager)
   {
      List<Bean<?>> beans = new CopyOnWriteArrayList<Bean<?>>();
      beans.addAll(parentManager.getBeans());
      
      ConcurrentSetMultiMap<Type, EventObserver<?>> registeredObservers = new ConcurrentSetHashMultiMap<Type, EventObserver<?>>();
      registeredObservers.deepPutAll(parentManager.getRegisteredObservers());
      Namespace rootNamespace = new Namespace(parentManager.getRootNamespace());
      
      return new ManagerImpl(
            parentManager.getServices(),
            beans,
            registeredObservers,
            rootNamespace,
            parentManager.getNewEnterpriseBeanMap(), 
            parentManager.getRiBeans(),
            parentManager.getClientProxyProvider(),
            parentManager.getContexts(),
            parentManager.getCurrentActivities(),
            parentManager.getSpecializedBeans(),
            parentManager.getEnabledDeploymentTypes(),
            parentManager.getIds()
            );
   }

   /**
    * Create a new manager
    * 
    * @param ejbServices the ejbResolver to use
    */
   private ManagerImpl(
         ServiceRegistry serviceRegistry, 
         List<Bean<?>> beans,
         ConcurrentSetMultiMap<Type, EventObserver<?>> registeredObservers,
         Namespace rootNamespace,
         Map<Class<?>, EnterpriseBean<?>> newEnterpriseBeans, 
         Map<String, RIBean<?>> riBeans,
         ClientProxyProvider clientProxyProvider,
         ConcurrentListMultiMap<Class<? extends Annotation>, Context> contexts,
         Set<CurrentActivity> currentActivities,
         Map<Bean<?>, Bean<?>> specializedBeans,
         List<Class<? extends Annotation>> enabledDeploymentTypes,
         AtomicInteger ids
         )
   {
      this.services = serviceRegistry;
      this.beanWithManagers = beans;
      this.newEnterpriseBeans = newEnterpriseBeans;
      this.riBeans = riBeans;
      this.clientProxyProvider = clientProxyProvider;
      this.contexts = contexts;
      this.currentActivities = currentActivities;
      this.specializedBeans = specializedBeans;
      this.registeredObservers = registeredObservers;
      setEnabledDeploymentTypes(enabledDeploymentTypes);
      this.rootNamespace = rootNamespace;
      this.ids = ids;
      this.id = ids.incrementAndGet();
      
      this.resolver = new Resolver(this);
      this.eventManager = new EventManager(this);
      this.nonContextualInjector = new NonContextualInjector(this);
      this.childActivities = new CopyOnWriteArraySet<ManagerImpl>();
      this.currentInjectionPoint = new ThreadLocal<Stack<InjectionPoint>>()
      {
         @Override
         protected Stack<InjectionPoint> initialValue()
         {
            return new Stack<InjectionPoint>();
         }
      };
   }

   /**
    * Set up the enabled deployment types, if none are specified by the user,
    * the default @Production and @Standard are used. For internal use.
    * 
    * @param enabledDeploymentTypes The enabled deployment types from
    *           web-beans.xml
    */
   protected void checkEnabledDeploymentTypes()
   {
      if (!this.enabledDeploymentTypes.get(0).equals(Standard.class))
      {
         throw new DeploymentException("@Standard must be the lowest precedence deployment type");
      }
   }

   protected void addWebBeansDeploymentTypes()
   {
      if (!this.enabledDeploymentTypes.contains(WebBean.class))
      {
         this.enabledDeploymentTypes.add(1, WebBean.class);
      }
   }

   /**
    * Registers a bean with the manager
    * 
    * @param bean The bean to register
    * @return A reference to manager
    * 
    * @see javax.enterprise.inject.spi.BeanManager#addBean(javax.inject.manager.Bean)
    */
   public void addBean(Bean<?> bean)
   {
      if (beanWithManagers.contains(bean))
      {
         return;
      }
      resolver.clear();
      beanWithManagers.add(bean);
      registerBeanNamespace(bean);
      for (ManagerImpl childActivity : childActivities)
      {
         childActivity.addBean(bean);
      }
      return;
   }

   /**
    * Resolve the disposal method for the given producer method. For internal
    * use.
    * 
    * @param apiType The API type to match
    * @param bindings The binding types to match
    * @return The set of matching disposal methods
    */
   public <T> Set<DisposalMethodBean<T>> resolveDisposalBeans(Class<T> apiType, Annotation... bindings)
   {
      // Correct?
      Set<Bean<?>> beans = getBeans(apiType, bindings);
      Set<DisposalMethodBean<T>> disposalBeans = new HashSet<DisposalMethodBean<T>>();
      for (Bean<?> bean : beans)
      {
         if (bean instanceof DisposalMethodBean)
         {
            disposalBeans.add((DisposalMethodBean<T>) bean);
         }
      }
      return disposalBeans;
   }

   public <T> Set<Observer<T>> resolveObservers(T event, Annotation... bindings)
   {
      Class<?> clazz = event.getClass();
      for (Annotation annotation : bindings)
      {
         if (!getServices().get(MetaDataCache.class).getBindingTypeModel(annotation.annotationType()).isValid())
         {
            throw new IllegalArgumentException("Not a binding type " + annotation);
         }
      }
      HashSet<Annotation> bindingAnnotations = new HashSet<Annotation>(Arrays.asList(bindings));
      if (bindingAnnotations.size() < bindings.length)
      {
         throw new DuplicateBindingTypeException("Duplicate binding types: " + bindings);
      }
      checkEventType(clazz);
      return eventManager.getObservers(event, bindings);
   }
   
   private void checkEventType(Type eventType)
   {
      Type[] types;
      if (eventType instanceof Class)
      {
         types = Reflections.getActualTypeArguments((Class<?>) eventType);
      }
      else if (eventType instanceof ParameterizedType)
      {
         types = ((ParameterizedType) eventType).getActualTypeArguments();
      }
      else
      {
         throw new IllegalArgumentException("Event type " + eventType + " isn't a concrete type");
      }
      for (Type type : types)
      {
         if (type instanceof WildcardType)
         {
            throw new IllegalArgumentException("Cannot provide an event type parameterized with a wildcard " + eventType);
         }
         if (type instanceof TypeVariable)
         {
            throw new IllegalArgumentException("Cannot provide an event type parameterized with a type parameter " + eventType);
         }
      }
   }

   /**
    * A strongly ordered, unmodifiable list of enabled deployment types
    * 
    * @return The ordered enabled deployment types known to the manager
    */
   public List<Class<? extends Annotation>> getEnabledDeploymentTypes()
   {
      return Collections.unmodifiableList(enabledDeploymentTypes);
   }

   /**
    * Set the enabled deployment types
    * 
    * @param enabledDeploymentTypes
    */
   public void setEnabledDeploymentTypes(List<Class<? extends Annotation>> enabledDeploymentTypes)
   {
      this.enabledDeploymentTypes = new ArrayList<Class<? extends Annotation>>(enabledDeploymentTypes);
      checkEnabledDeploymentTypes();
      addWebBeansDeploymentTypes();
   }

   
   public Set<Bean<?>> getBeans(Type beanType, Annotation... bindings)
   {
      return getBeans(ResolvableAnnotatedClass.of(beanType, bindings), bindings);
   }
   
   public Set<Bean<?>> getBeans(AnnotatedItem<?, ?> element, Annotation... bindings)
   {
      for (Annotation annotation : element.getAnnotationsAsSet())
      {
         if (!getServices().get(MetaDataCache.class).getBindingTypeModel(annotation.annotationType()).isValid())
         {
            throw new IllegalArgumentException("Not a binding type " + annotation);
         }
      }
      for (Type type : element.getActualTypeArguments())
      {
         if (type instanceof WildcardType)
         {
            throw new IllegalArgumentException("Cannot resolve a type parameterized with a wildcard " + element);
         }
         if (type instanceof TypeVariable)
         {
            throw new IllegalArgumentException("Cannot resolve a type parameterized with a type parameter " + element);
         }
      }
      if (bindings != null && bindings.length > element.getMetaAnnotations(BindingType.class).size())
      {
         throw new DuplicateBindingTypeException("Duplicate bindings (" + Arrays.asList(bindings) + ") type passed " + element.toString());
      }
      return resolver.get(element);
   }

   public Set<Bean<?>> getBeans(InjectionPoint injectionPoint)
   {
      boolean registerInjectionPoint = !injectionPoint.getType().equals(InjectionPoint.class);
      try
      {
         if (registerInjectionPoint)
         {
            currentInjectionPoint.get().push(injectionPoint);
         }
         // TODO Do this properly
         return getBeans(ResolvableAnnotatedClass.of(injectionPoint.getType(), injectionPoint.getBindings().toArray(new Annotation[0])));
      }
      finally
      {
         if (registerInjectionPoint)
         {
            currentInjectionPoint.get().pop();
         }
      }
   }

   /**
    * Wraps a collection of beans into a thread safe list. Since this overwrites
    * any existing list of beans in the manager, this should only be done on
    * startup and other controlled situations. Also maps the beans by
    * implementation class. For internal use.
    * 
    * @param beans The set of beans to add
    * @return A reference to the manager
    */
   // TODO Build maps in the deployer :-)
   public void setBeans(Set<RIBean<?>> beans)
   {
      synchronized (beans)
      {
         this.beanWithManagers = new CopyOnWriteArrayList<Bean<?>>(beans);
         for (RIBean<?> bean : beans)
         {
            if (bean instanceof NewEnterpriseBean)
            {
               newEnterpriseBeans.put(bean.getType(), (EnterpriseBean<?>) bean);
            }
            riBeans.put(bean.getId(), bean);
            registerBeanNamespace(bean);
         }
         resolver.clear();
      }
   }
   
   protected void registerBeanNamespace(Bean<?> bean)
   {
      if (bean.getName() != null && bean.getName().indexOf('.') > 0)
      {
         String name = bean.getName().substring(0, bean.getName().lastIndexOf('.'));
         String[] hierarchy = name.split("\\.");
         Namespace namespace = getRootNamespace();
         for (String s : hierarchy)
         {
            namespace = namespace.putIfAbsent(s);
         }
      }
   }

   /**
    * Gets the class-mapped beans. For internal use.
    * 
    * @return The bean map
    */
   public Map<Class<?>, EnterpriseBean<?>> getNewEnterpriseBeanMap()
   {
      return newEnterpriseBeans;
   }

   /**
    * The beans registered with the Web Bean manager. For internal use
    * 
    * @return The list of known beans
    */
   public List<Bean<?>> getBeans()
   {
      return Collections.unmodifiableList(beanWithManagers);
   }

   public Map<String, RIBean<?>> getRiBeans()
   {
      return Collections.unmodifiableMap(riBeans);
   }

   /**
    * Registers a context with the manager
    * 
    * @param context The context to add
    * @return A reference to the manager
    * 
    * @see javax.enterprise.inject.spi.BeanManager#addContext(javax.enterprise.context.spi.Context)
    */
   public void addContext(Context context)
   {
      contexts.put(context.getScopeType(), context);
   }

  
   public void addObserver(Observer<?> observer, Annotation... bindings)
   {
      addObserver(observer,eventManager.getTypeOfObserver(observer),bindings);
   }

   /**
    * Shortcut to register an ObserverImpl
    * 
    * @param <T>
    * @param observer
    */
   public <T> void addObserver(ObserverImpl<T> observer)
   {
      addObserver(observer, observer.getEventType(), observer.getBindingsAsArray());
   }

   public void addObserver(ObserverMethod<?, ?> observerMethod)
   {
      addObserver((Observer<?>)observerMethod, observerMethod.getObservedEventType(), 
            new ArrayList<Annotation>(observerMethod.getObservedEventBindings()).toArray(new Annotation[0]));
      
   }


   /**
    * Does the actual observer registration
    *  
    * @param observer
    * @param eventType
    * @param bindings
    * @return
    */
   public void addObserver(Observer<?> observer, Type eventType, Annotation... bindings)
   {
      checkEventType(eventType);
      this.eventManager.addObserver(observer, eventType, bindings);
      for (ManagerImpl childActivity : childActivities)
      {
         childActivity.addObserver(observer, eventType, bindings);
      }
   }
   
   public void removeObserver(Observer<?> observer)
   {
      eventManager.removeObserver(observer);
   }


   /**
    * Fires an event object with given event object for given bindings
    * 
    * @param event The event object to pass along
    * @param bindings The binding types to match
    * 
    * @see javax.enterprise.inject.spi.BeanManager#fireEvent(java.lang.Object,
    *      java.lang.annotation.Annotation[])
    */
   public void fireEvent(Object event, Annotation... bindings)
   {
      // Check the event object for template parameters which are not allowed by
      // the spec.
      if (Reflections.isParameterizedType(event.getClass()))
      {
         throw new IllegalArgumentException("Event type " + event.getClass().getName() + " is not allowed because it is a generic");
      }
      // Also check that the binding types are truly binding types
      for (Annotation binding : bindings)
      {
         if (!Reflections.isBindings(binding))
         {
            throw new IllegalArgumentException("Event type " + event.getClass().getName() + " cannot be fired with non-binding type " + binding.getClass().getName() + " specified");
         }
      }

      // Get the observers for this event. Although resolveObservers is
      // parameterized, this method is not, so we have to use
      // Observer<Object> for observers.
      Set<Observer<Object>> observers = resolveObservers(event, bindings);
      eventManager.notifyObservers(observers, event);
   }

   /**
    * Gets an active context of the given scope. Throws an exception if there
    * are no active contexts found or if there are too many matches
    * 
    * @param scopeType The scope to match
    * @return A single active context of the given scope
    * 
    * @see javax.enterprise.inject.spi.BeanManager#getContext(java.lang.Class)
    */
   public Context getContext(Class<? extends Annotation> scopeType)
   {
      List<Context> activeContexts = new ArrayList<Context>();
      for (Context context : contexts.get(scopeType))
      {
         if (context.isActive())
         {
            activeContexts.add(context);
         }
      }
      if (activeContexts.isEmpty())
      {
         throw new ContextNotActiveException("No active contexts for scope type " + scopeType.getName());
      }
      if (activeContexts.size() > 1)
      {
         throw new IllegalStateException("More than one context active for scope type " + scopeType.getName());
      }
      return activeContexts.iterator().next();
   }

   @Deprecated
   public <T> T getInstance(Bean<T> bean)
   {
      return getInstance(bean, true);
   }

   @Deprecated
   public <T> T getInstance(Bean<T> bean, boolean create)
   {
      if (create)
      {
         return (T) getInjectableReference(bean, CreationalContextImpl.of(bean));
      }
      else
      {
         return (T) getInjectableReference(bean, null);
      }
   }
   
   public Object getInjectableReference(Bean<?> bean, CreationalContext<?> creationalContext)
   {
      bean = getMostSpecializedBean(bean);
      if (getServices().get(MetaDataCache.class).getScopeModel(bean.getScopeType()).isNormal())
      {
         if (creationalContext != null || (creationalContext == null && getContext(bean.getScopeType()).get(bean) != null))
         {
            return clientProxyProvider.getClientProxy(this, bean);
         }
         else
         {
            return null;
         }
      }
      else
      {
         return getContext(bean.getScopeType()).get((Bean) bean, creationalContext);
      }
   }
   

   /** 
    * XXX this is not correct, as the current implementation of getInstance does not 
    * pay attention to what type the resulting instance needs to implement (non-Javadoc)
    * @see javax.enterprise.inject.spi.BeanManager#getReference(javax.enterprise.inject.spi.Bean, java.lang.reflect.Type)
    */
   public Object getReference(Bean<?> bean, Type beanType)
   {
      return getInstance(bean,true);  
   }

   @SuppressWarnings("unchecked")
   public Object getInjectableReference(InjectionPoint injectionPoint, CreationalContext<?> creationalContext)
   {
      boolean registerInjectionPoint = !injectionPoint.getType().equals(InjectionPoint.class);
      try
      {
         if (registerInjectionPoint)
         {
            currentInjectionPoint.get().push(injectionPoint);
         }
         AnnotatedItem<?, ?> element = ResolvableAnnotatedClass.of(injectionPoint.getType(), injectionPoint.getBindings().toArray(new Annotation[0]));
         Bean<?> resolvedBean = getBean(element, element.getBindingsAsArray());
         if (getServices().get(MetaDataCache.class).getScopeModel(resolvedBean.getScopeType()).isNormal() && !Proxies.isTypeProxyable(injectionPoint.getType()))
         {
            throw new UnproxyableResolutionException("Attempting to inject an unproxyable normal scoped bean " + resolvedBean + " into " + injectionPoint);
         }
         if (creationalContext instanceof CreationalContextImpl)
         {
            CreationalContextImpl<?> ctx = (CreationalContextImpl<?>) creationalContext;
            if (ctx.containsIncompleteInstance(resolvedBean))
            {
               return ctx.getIncompleteInstance(resolvedBean);
            }
            else
            {
               return getInjectableReference(resolvedBean, ctx.getCreationalContext(resolvedBean));
            }
         }
         else
         {
            return getInjectableReference(resolvedBean, creationalContext);
         }
      }
      finally
      {
         if (registerInjectionPoint)
         {
            currentInjectionPoint.get().pop();
         }
      }
   }

   /**
    * Gets an instance by name, returning null if none is found and throwing an
    * exception if too many beans match
    * 
    * @param name The name to match
    * @return An instance of the bean
    * 
    * @see javax.enterprise.inject.spi.BeanManager#getInstanceByName(java.lang.String)
    */
   @Deprecated
   public Object getInstanceByName(String name)
   {
      Set<Bean<?>> beans = getBeans(name);
      if (beans.size() == 0)
      {
         return null;
      }
      else if (beans.size() > 1)
      {
         throw new AmbiguousResolutionException("Resolved multiple Web Beans with " + name);
      }
      else
      {
         return getInstance(beans.iterator().next());
      }
   }

   /**
    * Returns an instance by API type and binding types
    * 
    * @param type The API type to match
    * @param bindings The binding types to match
    * @return An instance of the bean
    * 
    * @see javax.enterprise.inject.spi.BeanManager#getInstanceByType(java.lang.Class,
    *      java.lang.annotation.Annotation[])
    */
   @Deprecated
   public <T> T getInstanceByType(Class<T> type, Annotation... bindings)
   {
      return getInstanceByType(ResolvableAnnotatedClass.of(type, bindings), bindings);
   }

   /**
    * Returns an instance by type literal and binding types
    * 
    * @param type The type to match
    * @param bindings The binding types to match
    * @return An instance of the bean
    * 
    * @see javax.enterprise.inject.spi.BeanManager#getInstanceByType(javax.enterprise.inject.TypeLiteral,
    *      java.lang.annotation.Annotation[])
    */
   @Deprecated
   public <T> T getInstanceByType(TypeLiteral<T> type, Annotation... bindings)
   {
      return getInstanceByType(ResolvableAnnotatedClass.of(type, bindings), bindings);
   }

   /**
    * Resolve an instance, verify that the resolved bean can be instantiated,
    * and return
    * 
    * @param element The annotated item to match
    * @param bindings The binding types to match
    * @return An instance of the bean
    */
   @Deprecated
   public <T> T getInstanceByType(AnnotatedItem<T, ?> element, Annotation... bindings)
   {
      return getInstance(getBean(element, bindings));
   }

   public <T> Bean<T> getBean(AnnotatedItem<T, ?> element, Annotation... bindings)
   {
      Set<Bean<?>> beans = getBeans(element, bindings);
      if (beans.size() == 0)
      {
         throw new UnsatisfiedResolutionException(element + "Unable to resolve any Web Beans");
      }
      else if (beans.size() > 1)
      {
         throw new AmbiguousResolutionException(element + "Resolved multiple Web Beans");
      }
      Bean<T> bean = (Bean<T>) beans.iterator().next();
      boolean normalScoped = getServices().get(MetaDataCache.class).getScopeModel(bean.getScopeType()).isNormal();
      if (normalScoped && !Beans.isBeanProxyable(bean))
      {
         throw new UnproxyableResolutionException("Normal scoped bean " + bean + " is not proxyable");
      }
      return bean;
   }

   public Set<Bean<?>> getBeans(String name)
   {
      return resolver.get(name);
   }

   /**
    * Resolves a list of decorators based on API types and binding types Os
    * 
    * @param types The set of API types to match
    * @param bindings The binding types to match
    * @return A list of matching decorators
    * 
    * @see javax.enterprise.inject.spi.BeanManager#resolveDecorators(java.util.Set,
    *      java.lang.annotation.Annotation[])
    */
   public List<Decorator<?>> resolveDecorators(Set<Type> types, Annotation... bindings)
   {
      throw new UnsupportedOperationException();
   }

   /**
    * Resolves a list of interceptors based on interception type and interceptor
    * bindings
    * 
    * @param type The interception type to resolve
    * @param interceptorBindings The binding types to match
    * @return A list of matching interceptors
    * 
    * @see javax.enterprise.inject.spi.BeanManager#resolveInterceptors(javax.enterprise.inject.spi.InterceptionType,
    *      java.lang.annotation.Annotation[])
    */
   public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings)
   {
      throw new UnsupportedOperationException();
   }

   /**
    * Get the web bean resolver. For internal use
    * 
    * @return The resolver
    */
   public Resolver getResolver()
   {
      return resolver;
   }

   /**
    * Gets a string representation
    * 
    * @return A string representation
    */
   @Override
   public String toString()
   {
      StringBuilder buffer = new StringBuilder();
      buffer.append("Manager\n");
      buffer.append("Enabled deployment types: " + getEnabledDeploymentTypes() + "\n");
      buffer.append("Registered contexts: " + contexts.keySet() + "\n");
      buffer.append("Registered beans: " + getBeans().size() + "\n");
      buffer.append("Specialized beans: " + specializedBeans.size() + "\n");
      return buffer.toString();
   }

   public BeanManager parse(InputStream xmlStream)
   {
      throw new UnsupportedOperationException();
   }

   public ManagerImpl createActivity()
   {
      ManagerImpl childActivity = newChildManager(this);
      childActivities.add(childActivity);
      CurrentManager.add(childActivity);
      return childActivity;
   }

   public ManagerImpl setCurrent(Class<? extends Annotation> scopeType)
   {
      if (!getServices().get(MetaDataCache.class).getScopeModel(scopeType).isNormal())
      {
         throw new IllegalArgumentException("Scope must be a normal scope type " + scopeType);
      }
      currentActivities.add(new CurrentActivity(getContext(scopeType), this));
      return this;
   }
   
   public ManagerImpl getCurrent()
   {
      List<CurrentActivity> activeCurrentActivities = new ArrayList<CurrentActivity>();
      for (CurrentActivity currentActivity : currentActivities)
      {
         if (currentActivity.getContext().isActive())
         {
            activeCurrentActivities.add(currentActivity);
         }
      }
      if (activeCurrentActivities.size() == 0)
      {
         return CurrentManager.rootManager();
      } 
      else if (activeCurrentActivities.size() == 1)
      {
         return activeCurrentActivities.get(0).getManager();
      }
      throw new IllegalStateException("More than one current activity for an active context " + currentActivities);
   }

   public ServiceRegistry getServices()
   {
      return services;
   }

   /**
    * Accesses the factory used to create each instance of InjectionPoint that
    * is injected into web beans.
    * 
    * @return the factory
    */
   public InjectionPoint getInjectionPoint()
   {
      if (!currentInjectionPoint.get().empty())
      {
         return currentInjectionPoint.get().peek();
      }
      else
      {
         return null;
      }
   }

   /**
    * 
    * @return
    */
   public Map<Bean<?>, Bean<?>> getSpecializedBeans()
   {
      // TODO make this unmodifiable after deploy!
      return specializedBeans;
   }

   // Serialization

   protected Object readResolve()
   {
      return CurrentManager.get(id);
   }

   /**
    * Provides access to the executor service used for asynchronous tasks.
    * 
    * @return the ExecutorService for this manager
    */
   public ExecutorService getTaskExecutor()
   {
      return taskExecutor;
   }

   public void shutdown()
   {
      log.trace("Ending application");
      shutdownExecutors();
      ApplicationContext.instance().destroy();
      ApplicationContext.instance().setActive(false);
      ApplicationContext.instance().setBeanStore(null);
      CurrentManager.cleanup();
   }

   /**
    * Shuts down any executor services in the manager.
    */
   protected void shutdownExecutors()
   {
      taskExecutor.shutdown();
      try
      {
         // Wait a while for existing tasks to terminate
         if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS))
         {
            taskExecutor.shutdownNow(); // Cancel currently executing tasks
            // Wait a while for tasks to respond to being cancelled
            if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS))
            {
               // Log the error here
            }
         }
      }
      catch (InterruptedException ie)
      {
         // (Re-)Cancel if current thread also interrupted
         taskExecutor.shutdownNow();
         // Preserve interrupt status
         Thread.currentThread().interrupt();
      }
   }
   
   protected ClientProxyProvider getClientProxyProvider()
   {
      return clientProxyProvider;
   }
   
   protected ConcurrentListMultiMap<Class<? extends Annotation>, Context> getContexts()
   {
      return contexts;
   }
   
   protected AtomicInteger getIds()
   {
      return ids;
   }
   
   protected Set<CurrentActivity> getCurrentActivities()
   {
      return currentActivities;
   }
   
   public Integer getId()
   {
      return id;
   }
   
   public ConcurrentSetMultiMap<Type, EventObserver<?>> getRegisteredObservers()
   {
      return registeredObservers;
   }
   
   public Namespace getRootNamespace()
   {
      return rootNamespace;
   }


   public <T> InjectionTarget<T> createInjectionTarget(Class<T> type)
   {
      throw new UnsupportedOperationException("Not yet implemented");
   }

   public <T> InjectionTarget<T> createInjectionTarget(AnnotatedType<T> type)
   {
      throw new UnsupportedOperationException("Not yet implemented");
   }

   public <T> ManagedBean<T> createManagedBean(Class<T> type)
   {
      throw new UnsupportedOperationException("Not yet implemented");
   }

   public <T> ManagedBean<T> createManagedBean(AnnotatedType<T> type)
   {
      throw new UnsupportedOperationException("Not yet implemented");
   }



   public <X> Bean<X> getMostSpecializedBean(Bean<X> bean)
   {
      Bean<?> key = bean;
      while (specializedBeans.containsKey(key))
      {
         if (key == null)
         {
            System.out.println("null key " + bean);
         }
         key = specializedBeans.get(key);
      }
      return (Bean<X>) key;
   }


   public void validate(InjectionPoint injectionPoint)
   {
      throw new RuntimeException("Not yet implemented");
   }


   public Set<Annotation> getInterceptorBindingTypeDefinition(
         Class<? extends Annotation> bindingType)
   {
      throw new RuntimeException("Not yet implemented");
   }

   public Bean<?> getPassivationCapableBean(String id)
   {
      throw new RuntimeException("Not yet implemented");
   }

   public ScopeType getScopeDefinition(Class<? extends Annotation> scopeType)
   {
      return scopeType.getAnnotation(ScopeType.class);
   }

   public Set<Annotation> getStereotypeDefinition(
         Class<? extends Annotation> stereotype)
   {
      StereotypeModel<? extends Annotation> model = 
    	  getServices().get(MetaDataCache.class).getStereotype(stereotype);
      Set<Annotation> results = new HashSet<Annotation>();
      if (model.getDefaultDeploymentType() != null)
    	  results.add(model.getDefaultDeploymentType());
      if (model.getDefaultScopeType() != null)
    	  results.add(model.getDefaultScopeType());
      if (model.getInterceptorBindings() != null)
    	  results.addAll(model.getInterceptorBindings());
      
      return results;
   }

   public boolean isBindingType(Class<? extends Annotation> annotationType)
   {
      return annotationType.isAnnotationPresent(BindingType.class);
   }

   public boolean isInterceptorBindingType(
         Class<? extends Annotation> annotationType)
   {
	   return annotationType.isAnnotationPresent(InterceptorBindingType.class);
   }

   public boolean isScopeType(Class<? extends Annotation> annotationType)
   {
	   return annotationType.isAnnotationPresent(ScopeType.class);
   }

   public boolean isStereotype(Class<? extends Annotation> annotationType)
   {
      return annotationType.isAnnotationPresent(Stereotype.class);
   }

   public <X> Bean<? extends X> getHighestPrecedenceBean(Set<Bean<? extends X>> beans)
   {
	   if (beans.size() == 1)
	   {
		   return beans.iterator().next();
	   }
	   else if (beans.isEmpty()) 
	   {
		   return null;
	   }
	   
	   // make a copy so that the sort is stable with respect to new deployment types added through the SPI
	   // TODO This code needs to be in Resolver
	   // TODO This needs caching
      final List<Class<? extends Annotation>> enabledDeploymentTypes = getEnabledDeploymentTypes();
      
      SortedSet<Bean<? extends X>> sortedBeans = new TreeSet<Bean<? extends X>>(new Comparator<Bean<? extends X>>() 
      { 
		   public int compare(Bean<? extends X> o1, Bean<? extends X> o2) 
		   {
			   int diff = enabledDeploymentTypes.indexOf(o1) - enabledDeploymentTypes.indexOf(o2);
			   if (diff == 0)
			   {
				   throw new AmbiguousResolutionException();
			   }
			   return diff;
		   }
      });
      sortedBeans.addAll(beans);
      return sortedBeans.last();
   }
   
   public ELResolver getELResolver()
   {
      return new WebBeansELResolver();
   }

}
