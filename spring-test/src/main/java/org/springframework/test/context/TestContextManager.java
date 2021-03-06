/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.test.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.util.Assert;

/**
 * {@code TestContextManager} is the main entry point into the <em>Spring
 * TestContext Framework</em>, which provides support for loading and accessing
 * {@link org.springframework.context.ApplicationContext application contexts},
 * dependency injection of test instances,
 * {@link org.springframework.transaction.annotation.Transactional transactional}
 * execution of test methods, etc.
 *
 * <p>Specifically, a {@code TestContextManager} is responsible for managing a
 * single {@link TestContext} and signaling events to all registered
 * {@link TestExecutionListener TestExecutionListeners} at well defined test
 * execution points:
 *
 * <ul>
 * <li>{@link #beforeTestClass() before test class execution}: prior to any
 * <em>before class methods</em> of a particular testing framework (e.g., JUnit
 * 4's {@link org.junit.BeforeClass @BeforeClass})</li>
 * <li>{@link #prepareTestInstance(Object) test instance preparation}:
 * immediately following instantiation of the test instance</li>
 * <li>{@link #beforeTestMethod(Object, Method) before test method execution}:
 * prior to any <em>before methods</em> of a particular testing framework (e.g.,
 * JUnit 4's {@link org.junit.Before @Before})</li>
 * <li>{@link #afterTestMethod(Object, Method, Throwable) after test method
 * execution}: after any <em>after methods</em> of a particular testing
 * framework (e.g., JUnit 4's {@link org.junit.After @After})</li>
 * <li>{@link #afterTestClass() after test class execution}: after any
 * <em>after class methods</em> of a particular testing framework (e.g., JUnit
 * 4's {@link org.junit.AfterClass @AfterClass})</li>
 * </ul>
 *
 * @author Sam Brannen
 * @author Juergen Hoeller
 * @since 2.5
 * @see BootstrapWith
 * @see BootstrapContext
 * @see TestContextBootstrapper
 * @see TestContext
 * @see TestExecutionListener
 * @see TestExecutionListeners
 * @see ContextConfiguration
 * @see ContextHierarchy
 * @see org.springframework.test.context.transaction.TransactionConfiguration
 */
public class TestContextManager {

	private static final Log logger = LogFactory.getLog(TestContextManager.class);

	/**
	 * Cache of Spring application contexts.
	 * <p>This needs to be static, since test instances may be destroyed and
	 * recreated between invocations of individual test methods, as is the case
	 * with JUnit.
	 */
	static final ContextCache contextCache = new ContextCache();

	private final TestContext testContext;

	private final TestContextBootstrapper testContextBootstrapper;

	private final List<TestExecutionListener> testExecutionListeners = new ArrayList<TestExecutionListener>();


	/**
	 * Construct a new {@code TestContextManager} for the specified {@linkplain Class test class}
	 * and automatically {@link #registerTestExecutionListeners register} the
	 * {@link TestExecutionListener TestExecutionListeners} configured for the test class
	 * via the {@link TestExecutionListeners @TestExecutionListeners} annotation.
	 * @param testClass the test class to be managed
	 * @see #registerTestExecutionListeners
	 */
	public TestContextManager(Class<?> testClass) {
		CacheAwareContextLoaderDelegate cacheAwareContextLoaderDelegate = new DefaultCacheAwareContextLoaderDelegate(contextCache);
		BootstrapContext bootstrapContext = new DefaultBootstrapContext(testClass, cacheAwareContextLoaderDelegate);
		this.testContextBootstrapper = BootstrapUtils.resolveTestContextBootstrapper(bootstrapContext);
		this.testContext = new DefaultTestContext(this.testContextBootstrapper);
		registerTestExecutionListeners(this.testContextBootstrapper.getTestExecutionListeners());
	}


	/**
	 * Get the {@link TestContext} managed by this {@code TestContextManager}.
	 */
	protected final TestContext getTestContext() {
		return this.testContext;
	}

	/**
	 * Register the supplied list of {@link TestExecutionListener TestExecutionListeners}
	 * by appending them to the list of listeners used by this {@code TestContextManager}.
	 * @see #registerTestExecutionListeners(TestExecutionListener...)
	 */
	public void registerTestExecutionListeners(List<TestExecutionListener> testExecutionListeners) {
		registerTestExecutionListeners(testExecutionListeners.toArray(new TestExecutionListener[testExecutionListeners.size()]));
	}

	/**
	 * Register the supplied array of {@link TestExecutionListener TestExecutionListeners}
	 * by appending them to the list of listeners used by this {@code TestContextManager}.
	 */
	public void registerTestExecutionListeners(TestExecutionListener... testExecutionListeners) {
		for (TestExecutionListener listener : testExecutionListeners) {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering TestExecutionListener: " + listener);
			}
			this.testExecutionListeners.add(listener);
		}
	}

	/**
	 * Get the current {@link TestExecutionListener TestExecutionListeners}
	 * registered for this {@code TestContextManager}.
	 * <p>Allows for modifications, e.g. adding a listener to the beginning of the list.
	 * However, make sure to keep the list stable while actually executing tests.
	 */
	public final List<TestExecutionListener> getTestExecutionListeners() {
		return this.testExecutionListeners;
	}

	/**
	 * Get a copy of the {@link TestExecutionListener TestExecutionListeners}
	 * registered for this {@code TestContextManager} in reverse order.
	 */
	private List<TestExecutionListener> getReversedTestExecutionListeners() {
		List<TestExecutionListener> listenersReversed = new ArrayList<TestExecutionListener>(getTestExecutionListeners());
		Collections.reverse(listenersReversed);
		return listenersReversed;
	}

	/**
	 * Hook for pre-processing a test class <em>before</em> execution of any
	 * tests within the class. Should be called prior to any framework-specific
	 * <em>before class methods</em> (e.g., methods annotated with JUnit's
	 * {@link org.junit.BeforeClass @BeforeClass}).
	 * <p>An attempt will be made to give each registered
	 * {@link TestExecutionListener} a chance to pre-process the test class
	 * execution. If a listener throws an exception, however, the remaining
	 * registered listeners will <strong>not</strong> be called.
	 * @throws Exception if a registered TestExecutionListener throws an
	 * exception
	 * @see #getTestExecutionListeners()
	 */
	public void beforeTestClass() throws Exception {
		Class<?> testClass = getTestContext().getTestClass();
		if (logger.isTraceEnabled()) {
			logger.trace("beforeTestClass(): class [" + testClass.getName() + "]");
		}
		getTestContext().updateState(null, null, null);

		for (TestExecutionListener testExecutionListener : getTestExecutionListeners()) {
			try {
				testExecutionListener.beforeTestClass(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener +
						"] to process 'before class' callback for test class [" + testClass + "]", ex);
				throw ex;
			}
		}
	}

	/**
	 * Hook for preparing a test instance prior to execution of any individual
	 * test methods, for example for injecting dependencies, etc. Should be
	 * called immediately after instantiation of the test instance.
	 * <p>The managed {@link TestContext} will be updated with the supplied
	 * {@code testInstance}.
	 * <p>An attempt will be made to give each registered
	 * {@link TestExecutionListener} a chance to prepare the test instance. If a
	 * listener throws an exception, however, the remaining registered listeners
	 * will <strong>not</strong> be called.
	 * @param testInstance the test instance to prepare (never {@code null})
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void prepareTestInstance(Object testInstance) throws Exception {
		Assert.notNull(testInstance, "testInstance must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("prepareTestInstance(): instance [" + testInstance + "]");
		}
		getTestContext().updateState(testInstance, null, null);

		for (TestExecutionListener testExecutionListener : getTestExecutionListeners()) {
			try {
				testExecutionListener.prepareTestInstance(getTestContext());
			}
			catch (Exception ex) {
				logger.error("Caught exception while allowing TestExecutionListener [" + testExecutionListener +
						"] to prepare test instance [" + testInstance + "]", ex);
				throw ex;
			}
		}
	}

	/**
	 * Hook for pre-processing a test <em>before</em> execution of the supplied
	 * {@link Method test method}, for example for setting up test fixtures,
	 * starting a transaction, etc. Should be called prior to any
	 * framework-specific <em>before methods</em> (e.g., methods annotated with
	 * JUnit's {@link org.junit.Before @Before}).
	 * <p>The managed {@link TestContext} will be updated with the supplied
	 * {@code testInstance} and {@code testMethod}.
	 * <p>An attempt will be made to give each registered
	 * {@link TestExecutionListener} a chance to pre-process the test method
	 * execution. If a listener throws an exception, however, the remaining
	 * registered listeners will <strong>not</strong> be called.
	 * @param testInstance the current test instance (never {@code null})
	 * @param testMethod the test method which is about to be executed on the
	 * test instance
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void beforeTestMethod(Object testInstance, Method testMethod) throws Exception {
		Assert.notNull(testInstance, "Test instance must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("beforeTestMethod(): instance [" + testInstance + "], method [" + testMethod + "]");
		}
		getTestContext().updateState(testInstance, testMethod, null);

		for (TestExecutionListener testExecutionListener : getTestExecutionListeners()) {
			try {
				testExecutionListener.beforeTestMethod(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener +
						"] to process 'before' execution of test method [" + testMethod + "] for test instance [" +
						testInstance + "]", ex);
				throw ex;
			}
		}
	}

	/**
	 * Hook for post-processing a test <em>after</em> execution of the supplied
	 * {@link Method test method}, for example for tearing down test fixtures,
	 * ending a transaction, etc. Should be called after any framework-specific
	 * <em>after methods</em> (e.g., methods annotated with JUnit's
	 * {@link org.junit.After @After}).
	 * <p>The managed {@link TestContext} will be updated with the supplied
	 * {@code testInstance}, {@code testMethod}, and
	 * {@code exception}.
	 * <p>Each registered {@link TestExecutionListener} will be given a chance to
	 * post-process the test method execution. If a listener throws an
	 * exception, the remaining registered listeners will still be called, but
	 * the first exception thrown will be tracked and rethrown after all
	 * listeners have executed. Note that registered listeners will be executed
	 * in the opposite order in which they were registered.
	 * @param testInstance the current test instance (never {@code null})
	 * @param testMethod the test method which has just been executed on the
	 * test instance
	 * @param exception the exception that was thrown during execution of the
	 * test method or by a TestExecutionListener, or {@code null} if none
	 * was thrown
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void afterTestMethod(Object testInstance, Method testMethod, Throwable exception) throws Exception {
		Assert.notNull(testInstance, "testInstance must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("afterTestMethod(): instance [" + testInstance + "], method [" + testMethod +
					"], exception [" + exception + "]");
		}
		getTestContext().updateState(testInstance, testMethod, exception);

		Exception afterTestMethodException = null;
		// Traverse the TestExecutionListeners in reverse order to ensure proper
		// "wrapper"-style execution of listeners.
		for (TestExecutionListener testExecutionListener : getReversedTestExecutionListeners()) {
			try {
				testExecutionListener.afterTestMethod(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener +
						"] to process 'after' execution for test: method [" + testMethod + "], instance [" +
						testInstance + "], exception [" + exception + "]", ex);
				if (afterTestMethodException == null) {
					afterTestMethodException = ex;
				}
			}
		}
		if (afterTestMethodException != null) {
			throw afterTestMethodException;
		}
	}

	/**
	 * Hook for post-processing a test class <em>after</em> execution of all
	 * tests within the class. Should be called after any framework-specific
	 * <em>after class methods</em> (e.g., methods annotated with JUnit's
	 * {@link org.junit.AfterClass @AfterClass}).
	 * <p>Each registered {@link TestExecutionListener} will be given a chance to
	 * post-process the test class. If a listener throws an exception, the
	 * remaining registered listeners will still be called, but the first
	 * exception thrown will be tracked and rethrown after all listeners have
	 * executed. Note that registered listeners will be executed in the opposite
	 * order in which they were registered.
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void afterTestClass() throws Exception {
		Class<?> testClass = getTestContext().getTestClass();
		if (logger.isTraceEnabled()) {
			logger.trace("afterTestClass(): class [" + testClass.getName() + "]");
		}
		getTestContext().updateState(null, null, null);

		Exception afterTestClassException = null;
		// Traverse the TestExecutionListeners in reverse order to ensure proper
		// "wrapper"-style execution of listeners.
		for (TestExecutionListener testExecutionListener : getReversedTestExecutionListeners()) {
			try {
				testExecutionListener.afterTestClass(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener +
						"] to process 'after class' callback for test class [" + testClass + "]", ex);
				if (afterTestClassException == null) {
					afterTestClassException = ex;
				}
			}
		}
		if (afterTestClassException != null) {
			throw afterTestClassException;
		}
	}

}
