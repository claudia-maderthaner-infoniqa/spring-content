package it;

import static com.github.paulcwarren.ginkgo4j.Ginkgo4jDSL.BeforeEach;
import static com.github.paulcwarren.ginkgo4j.Ginkgo4jDSL.Context;
import static com.github.paulcwarren.ginkgo4j.Ginkgo4jDSL.Describe;
import static com.github.paulcwarren.ginkgo4j.Ginkgo4jDSL.It;
import static com.github.paulcwarren.ginkgo4j.Ginkgo4jDSL.JustBeforeEach;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.junit.runner.RunWith;
import org.springframework.content.commons.repository.Store;
import internal.org.springframework.content.commons.store.factory.StoreFactory;
import org.springframework.content.commons.repository.factory.testsupport.TestContentStore;
import org.springframework.content.commons.repository.factory.testsupport.TestStoreFactoryBean;
import org.springframework.content.commons.storeservice.StoreFilter;
import org.springframework.content.commons.storeservice.StoreInfo;
import org.springframework.content.commons.storeservice.StoreResolver;
import org.springframework.context.support.GenericApplicationContext;

import com.github.paulcwarren.ginkgo4j.Ginkgo4jRunner;

import internal.org.springframework.content.commons.storeservice.StoresImpl;

@RunWith(Ginkgo4jRunner.class)
public class StoresIT {

    private StoresImpl stores;

    private GenericApplicationContext context;
    private List<StoreFactory> factories = new ArrayList<>();
    private StoreResolver resolver;

    {
        Describe("#getStore", () -> {

            Context("when there are two stores that the filter matches but no store resolver", () -> {

                BeforeEach(() -> {
                    TestStoreFactoryBean factory1 = new TestStoreFactoryBean(WrongStore.class);
                    factory1.setBeanClassLoader(this.getClass().getClassLoader());
                    factories.add(factory1);

                    TestStoreFactoryBean factory2 = new TestStoreFactoryBean(RightStore.class);
                    factory2.setBeanClassLoader(this.getClass().getClassLoader());
                    factories.add(factory2);

                    context = new GenericApplicationContext();
                    context.registerBean("factory1", StoreFactory.class, () -> {return factory1;});
                    context.registerBean("factory2", StoreFactory.class, () -> {return factory2;});
                });

                JustBeforeEach(() -> {
                    context.refresh();
                    stores = new StoresImpl(context);
                    stores.afterPropertiesSet();
                });

                It("should return the right store", () -> {

                    try {
                        stores.getStore(Store.class, new StoreFilter() {
                            @Override
                            public String name() {
                                return "test";
                            }

                            @Override
                            public boolean matches(StoreInfo info) {
                                return true;
                            }
                        });
                        fail("exception not thrown");
                    } catch (Exception e) {
                        assertThat(e.getMessage(), containsString("unable to resolve store"));
                    }
                });
            });

            Context("when there are two stores that the filter matches and a store resolver", () -> {

                BeforeEach(() -> {
                    TestStoreFactoryBean factory1 = new TestStoreFactoryBean(WrongStore.class);
                    factory1.setBeanClassLoader(this.getClass().getClassLoader());
                    factories.add(factory1);

                    TestStoreFactoryBean factory2 = new TestStoreFactoryBean(RightStore.class);
                    factory2.setBeanClassLoader(this.getClass().getClassLoader());
                    factories.add(factory2);

                    context = new GenericApplicationContext();
                    context.registerBean("factory1", StoreFactory.class, () -> {return factory1;});
                    context.registerBean("factory2", StoreFactory.class, () -> {return factory2;});
                });

                JustBeforeEach(() -> {
                    context.refresh();
                    stores = new StoresImpl(context);
                    stores.afterPropertiesSet();

                    stores.addStoreResolver("test", new StoreResolver() {
                        @Override
                        public StoreInfo resolve(StoreInfo... stores) {
                            for (StoreInfo info : stores) {
                                if (info.getInterface().equals(RightStore.class)) {
                                    return info;
                                }
                            }
                            return null;
                        }
                    });
                });

                It("should return the right store", () -> {
                    StoreInfo info = stores.getStore(Store.class, new StoreFilter() {
                        @Override
                        public String name() {
                            return "test";
                        }
                        @Override
                        public boolean matches(StoreInfo info) {
                            return true;
                        }
                    });

                    assertThat(info.getInterface(), is(RightStore.class));
                });
            });
        });
    }

    public interface RightStore extends TestContentStore<Object, Serializable>{};
    public interface WrongStore extends TestContentStore<Object, Serializable>{};
}
