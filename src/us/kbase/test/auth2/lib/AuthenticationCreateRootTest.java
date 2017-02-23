package us.kbase.test.auth2.lib;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static us.kbase.test.auth2.TestCommon.assertClear;
import static us.kbase.test.auth2.TestCommon.set;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;

import org.junit.Test;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.ImmutableMap;

import us.kbase.auth2.cryptutils.PasswordCrypt;
import us.kbase.auth2.lib.AuthConfig;
import us.kbase.auth2.lib.AuthConfigSet;
import us.kbase.auth2.lib.AuthUser;
import us.kbase.auth2.lib.Authentication;
import us.kbase.auth2.lib.CollectingExternalConfig;
import us.kbase.auth2.lib.CollectingExternalConfig.CollectingExternalConfigMapper;
import us.kbase.auth2.lib.DisplayName;
import us.kbase.auth2.lib.EmailAddress;
import us.kbase.auth2.lib.LocalUser;
import us.kbase.auth2.lib.NewRootUser;
import us.kbase.auth2.lib.Password;
import us.kbase.auth2.lib.Role;
import us.kbase.auth2.lib.UserDisabledState;
import us.kbase.auth2.lib.UserName;
import us.kbase.auth2.lib.exceptions.NoSuchUserException;
import us.kbase.auth2.lib.exceptions.UserExistsException;
import us.kbase.auth2.lib.identity.IdentityProviderSet;
import us.kbase.auth2.lib.storage.AuthStorage;
import us.kbase.test.auth2.TestCommon;

public class AuthenticationCreateRootTest {
	
	/* Some of these tests are time sensitive and verify() won't work because the object is
	 * changed after the mocked method is called. Instead use an Answer:
	 * 
	 * http://stackoverflow.com/questions/9085738/can-mockito-verify-parameters-based-on-their-values-at-the-time-of-method-call
	 * 
	 */
	
	private class TestAuth {
		final AuthStorage storageMock;
		final Authentication auth;
		
		public TestAuth(AuthStorage storageMock, Authentication auth) {
			this.storageMock = storageMock;
			this.auth = auth;
		}
	}
	
	/* Note that the salt is *not* checked. the pwd hash is checked by regenerating from the
	 * incoming user's salt.
	 * The created date is checked to be within 200 ms of the current time.
	 */
	private class RootUserAnswerMatcher implements Answer<Void> {

		private final Password pwd;
		public byte[] savedSalt;
		public byte[] savedHash;
		
		public RootUserAnswerMatcher(final Password pwd) {
			this.pwd = pwd;
		}
		
		@Override
		public Void answer(final InvocationOnMock inv) throws Throwable {
			final NewRootUser user = inv.getArgument(0);
			savedSalt = user.getSalt();
			savedHash = user.getPasswordHash();
			/* sort of bogus to use the same pwd gen code from the method under test in the test
			 * but the pwd gen code is tested elsewhere and trying to do this manually
			 * would be a major pain. Maybe look into mocking the pwd gen code..?
			 * 
			 * salt is entirely random so salt is impossible to test other than getting the size
			 */
			final byte[] hash = new PasswordCrypt().getEncryptedPassword(
					pwd.getPassword(), savedSalt);
			final NewRootUser exp = new NewRootUser(EmailAddress.UNKNOWN, new DisplayName("root"),
					hash, savedSalt);
			/* omg you bad person */
			final Field f = AuthUser.class.getDeclaredField("created");
			f.setAccessible(true);
			f.set(exp, user.getCreated().getTime());
			assertThat("local user does not match. Salt was not checked. " +
					"Created date was not checked.", user, is(exp));
			assertThat("creation date not within 200ms",
					TestCommon.dateWithin(user.getCreated(), 200), is(true));
			assertThat("salt not 8 bytes", user.getSalt().length, is(8));
			return null;
		}
		
	}

	@Test
	public void createRoot() throws Exception {
		final TestAuth testauth = initTestAuth();
		final AuthStorage storage = testauth.storageMock;
		final Authentication auth = testauth.auth;
		final Password pwd = new Password("foobarbazbat".toCharArray());
		// pwd will be cleared before the method call
		final Password pwd2 = new Password("foobarbazbat".toCharArray());
		final RootUserAnswerMatcher matcher = new RootUserAnswerMatcher(pwd2);
		doAnswer(matcher).when(storage).createLocalUser(any(NewRootUser.class));
		auth.createRoot(pwd);
		final char[] clearpwd = {'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'};
		assertThat("password not cleared", pwd.getPassword(), is(clearpwd));
		assertClear(matcher.savedSalt);
		assertClear(matcher.savedHash);
		/* ensure method was called at least once
		 * Usually not necessary when mocking the call, but since createLU returns null
		 * need to ensure the method was actually called and therefore the RootuserAnswerMatcher
		 * ran
		 */
		verify(storage).createLocalUser(any());
	}

	private TestAuth initTestAuth() throws Exception {
		final AuthStorage storage = mock(AuthStorage.class);
		final AuthConfig ac =  new AuthConfig(AuthConfig.DEFAULT_LOGIN_ALLOWED, null,
				AuthConfig.DEFAULT_TOKEN_LIFETIMES_MS);
		when(storage.getConfig(isA(CollectingExternalConfigMapper.class))).thenReturn(
				new AuthConfigSet<>(ac,
						new CollectingExternalConfig(ImmutableMap.of("thing", "foo"))));
		
		return new TestAuth(storage, new Authentication(
				storage, new IdentityProviderSet(), new TestExternalConfig("foo")));
	}
	
	private class ChangePasswordAnswerMatcher implements Answer<Void> {
		
		private final UserName name;
		private final Password pwd;
		private final boolean forceReset;
		private byte[] savedSalt;
		private byte[] savedHash;
		
		public ChangePasswordAnswerMatcher(
				final UserName name,
				final Password pwd,
				final boolean forceReset) {
			this.name = name;
			this.pwd = pwd;
			this.forceReset = forceReset;
		}

		@Override
		public Void answer(final InvocationOnMock args) throws Throwable {
			final UserName un = args.getArgument(0);
			savedHash = args.getArgument(1);
			savedSalt = args.getArgument(2);
			final boolean forceReset = args.getArgument(3);
			/* sort of bogus to use the same pwd gen code from the method under test in the test
			 * but the pwd gen code is tested elsewhere and trying to do this manually
			 * would be a major pain. Maybe look into mocking the pwd gen code..?
			 * 
			 * salt is entirely random so salt is impossible to test other than getting the size
			 */
			final byte[] hash = new PasswordCrypt().getEncryptedPassword(
					pwd.getPassword(), savedSalt);
			assertThat("incorrect username", un, is(name));
			assertThat("incorrect forcereset", forceReset, is(this.forceReset));
			assertThat("incorrect hash", savedHash, is(hash));
			assertThat("salt not 8 bytes", savedSalt.length, is(8));
			return null;
		}
	}
	
	@Test
	public void resetRootPassword() throws Exception {
		final TestAuth testauth = initTestAuth();
		final AuthStorage storage = testauth.storageMock;
		final Authentication auth = testauth.auth;
		final Password pwd = new Password("foobarbazbat".toCharArray());
		// pwd will be cleared before the method call
		final Password pwd2 = new Password("foobarbazbat".toCharArray());
		final ChangePasswordAnswerMatcher matcher =
				new ChangePasswordAnswerMatcher(UserName.ROOT, pwd2, false);
		doThrow(new UserExistsException(UserName.ROOT.getName()))
				.when(storage).createLocalUser(any(NewRootUser.class));
		doAnswer(matcher).when(storage).changePassword(
				eq(UserName.ROOT), any(byte[].class), any(byte[].class), eq(false));
		when(storage.getUser(UserName.ROOT)).thenReturn(new NewRootUser(EmailAddress.UNKNOWN,
				new DisplayName("root"), new byte[10], new byte[8]));
		auth.createRoot(pwd);
		final char[] clearpwd = {'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'};
		assertThat("password not cleared", pwd.getPassword(), is(clearpwd));
		assertClear(matcher.savedSalt);
		assertClear(matcher.savedHash);
		
		/* ensure method was called at least once
		 * Usually not necessary when mocking the call, but since changepwd returns null
		 * need to ensure the method was actually called and therefore the matcher ran
		 */
		verify(storage).changePassword(
				eq(UserName.ROOT), any(byte[].class), any(byte[].class), eq(false));
	}
	
	@Test
	public void catastrophicFail() throws Exception {
		final TestAuth testauth = initTestAuth();
		final AuthStorage storage = testauth.storageMock;
		final Authentication auth = testauth.auth;
		doThrow(new UserExistsException(UserName.ROOT.getName()))
				.when(storage).createLocalUser(any(NewRootUser.class));
		// ignore the change password call, tested elsewhere
		when(storage.getUser(UserName.ROOT)).thenThrow(
				new NoSuchUserException(UserName.ROOT.getName()));
		try {
			auth.createRoot(new Password("foobarbazbat".toCharArray()));
			fail("expected exception");
		} catch (RuntimeException e) {
			TestCommon.assertExceptionCorrect(e,
					new RuntimeException("OK. This is really bad. I give up."));
		}
	}
	
	@Test
	public void enableRoot() throws Exception {
		final TestAuth testauth = initTestAuth();
		final AuthStorage storage = testauth.storageMock;
		final Authentication auth = testauth.auth;
		doThrow(new UserExistsException(UserName.ROOT.getName()))
				.when(storage).createLocalUser(any(NewRootUser.class));
		// ignore the change password call, tested elsewhere
		final LocalUser disabled = new LocalUser(UserName.ROOT, EmailAddress.UNKNOWN,
				new DisplayName("root"), set(Role.ROOT), Collections.emptySet(),
				new Date(), new Date(), new UserDisabledState("foo", UserName.ROOT, new Date()),
				new byte[10], new byte[8], false, null);
		when(storage.getUser(UserName.ROOT)).thenReturn(disabled);
		auth.createRoot(new Password("foobarbazbat".toCharArray()));
		verify(storage).enableAccount(UserName.ROOT, UserName.ROOT);
	}
	
	@Test
	public void nullPwd() throws Exception {
		try {
			initTestAuth().auth.createRoot(null);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new NullPointerException("pwd"));
		}
	}
}