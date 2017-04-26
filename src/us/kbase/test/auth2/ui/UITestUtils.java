package us.kbase.test.auth2.ui;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static us.kbase.test.auth2.TestCommon.set;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import de.danielbechler.diff.ObjectDifferBuilder;
import de.danielbechler.diff.node.DiffNode;
import de.danielbechler.diff.node.ToMapPrintingVisitor;
import de.danielbechler.diff.path.NodePath;
import us.kbase.auth2.lib.Authentication;
import us.kbase.auth2.lib.DisplayName;
import us.kbase.auth2.lib.EmailAddress;
import us.kbase.auth2.lib.Password;
import us.kbase.auth2.lib.Role;
import us.kbase.auth2.lib.TokenCreationContext;
import us.kbase.auth2.lib.UserName;
import us.kbase.auth2.lib.exceptions.AuthException;
import us.kbase.auth2.lib.token.IncomingToken;
import us.kbase.auth2.service.AuthExternalConfig;
import us.kbase.common.test.RegexMatcher;
import us.kbase.test.auth2.MongoStorageTestManager;
import us.kbase.test.auth2.TestCommon;

public class UITestUtils {
	
	/** Set up a root account and an admin account and return a token for the admin.
	 * @param manager the mongo test manger containing the mongo storage instance that will be
	 * affected.
	 * @return a new token for an admin called 'admin' with CREATE_ADMIN and ADMIN roles.
	 * @throws Exception if bad things happen.
	 */
	public static IncomingToken getAdminToken(final MongoStorageTestManager manager)
			throws Exception {
		final String rootpwd = "foobarwhoowhee";
		when(manager.mockClock.instant()).thenReturn(Instant.now());
		final Authentication auth = new Authentication(
				manager.storage, set(), AuthExternalConfig.SET_DEFAULT);
		auth.createRoot(new Password(rootpwd.toCharArray()));
		final String roottoken = auth.localLogin(UserName.ROOT,
				new Password(rootpwd.toCharArray()),
				TokenCreationContext.getBuilder().build()).getToken().get().getToken();
		final Password admintemppwd = auth.createLocalUser(
				new IncomingToken(roottoken), new UserName("admin"), new DisplayName("a"),
				new EmailAddress("f@g.com"));
		auth.updateRoles(new IncomingToken(roottoken), new UserName("admin"),
				set(Role.CREATE_ADMIN), set());
		final String adminpwd = "foobarwhoowhee2";
		auth.localPasswordChange(new UserName("admin"), admintemppwd,
				new Password(adminpwd.toCharArray()));
		final String admintoken = auth.localLogin(new UserName("admin"),
				new Password(adminpwd.toCharArray()), TokenCreationContext.getBuilder().build())
				.getToken().get().getToken();
		auth.updateRoles(new IncomingToken(admintoken), new UserName("admin"), set(Role.ADMIN),
				set());
		return new IncomingToken(admintoken);
	}

	public static void assertErrorCorrect(
			final int expectedHTTPCode,
			final String expectedHTTPStatus,
			final AuthException expectedException,
			final Map<String, Object> error) {
		
		final Map<String, Object> innerExpected = new HashMap<>();
		innerExpected.put("httpcode", expectedHTTPCode);
		innerExpected.put("httpstatus", expectedHTTPStatus);
		innerExpected.put("appcode", expectedException.getErr().getErrorCode());
		innerExpected.put("apperror", expectedException.getErr().getError());
		innerExpected.put("message", expectedException.getMessage());
		
		final Map<String, Object> expected = ImmutableMap.of("error", innerExpected);
		
		if (!error.containsKey("error")) {
			fail("error object has no error key");
		}
		
		@SuppressWarnings("unchecked")
		final Map<String, Object> inner = (Map<String, Object>) error.get("error");
		
		final String callid = (String) inner.get("callid");
		final long time = (long) inner.get("time");
		inner.remove("callid");
		inner.remove("time");
		
		assertThat("incorrect error structure less callid and time", error, is(expected));
		assertThat("incorrect call id", callid, RegexMatcher.matches("\\d{16}"));
		TestCommon.assertCloseToNow(time);
	}
	
	// note ObjectDiffer does NOT check sorted lists are sorted
	// this really kind of sucks, but it's better for large shallow objects
	// easy enough to do a straight equals if needed
	public static void assertObjectsEqual(final Object got, final Object expected) {
		final DiffNode diff = ObjectDifferBuilder.buildDefault().compare(got, expected);
		final ToMapPrintingVisitor visitor = new ToMapPrintingVisitor(got, expected);
		diff.visit(visitor);
		
		assertThat("non empty structure diff", visitor.getMessages(),
				is(ImmutableMap.of(NodePath.withRoot(), "Property at path '/' has not changed")));
	}

}