package us.kbase.test.auth2.lib.config;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import nl.jqno.equalsverifier.EqualsVerifier;
import us.kbase.auth2.lib.config.CollectingExternalConfig;
import us.kbase.auth2.lib.config.CollectingExternalConfig.CollectingExternalConfigMapper;
import us.kbase.auth2.lib.config.ConfigItem;
import us.kbase.auth2.lib.exceptions.ExternalConfigMappingException;
import us.kbase.test.auth2.TestCommon;

public class CollectingExternalConfigTest {

	@Test
	public void configEquals() {
		EqualsVerifier.forClass(CollectingExternalConfig.class).usingGetClass().verify();
	}
	
	@Test
	public void construct() {
		final CollectingExternalConfig cfg = new CollectingExternalConfig(
				ImmutableMap.of("foo", ConfigItem.state("bar")));
		assertThat("incorrect config", cfg.getMap(),
				is(ImmutableMap.of("foo", ConfigItem.state("bar"))));
	}
	
	@Test
	public void map() throws ExternalConfigMappingException {
		final CollectingExternalConfig cfg = new CollectingExternalConfigMapper().fromMap(
				ImmutableMap.of("baz", ConfigItem.state("bat")));
		assertThat("incorrect config", cfg.getMap(),
				is(ImmutableMap.of("baz", ConfigItem.state("bat"))));
	}
	
	@Test
	public void unsupportedOp() {
		final CollectingExternalConfig cfg = new CollectingExternalConfig(
				ImmutableMap.of("foo", ConfigItem.state("bar")));
		try {
			cfg.toMap();
			fail("expected exception");
		} catch (UnsupportedOperationException e) {
			// test passed
		}
	}
	
	@Test
	public void failConstruct() {
		try {
			new CollectingExternalConfig(null);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new NullPointerException("map"));
		}
	}
	
}
