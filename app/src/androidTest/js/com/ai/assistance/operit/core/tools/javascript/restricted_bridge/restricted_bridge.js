'use strict';

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function expectRejected(label, action) {
  try {
    action();
  } catch (error) {
    const message = String(error && error.message ? error.message : error);
    assert(
      message.includes('not allowed') ||
      message.includes('disabled') ||
        message.includes('serialization') ||
        message.includes('cyclic') ||
        message.includes('not found') ||
        message.includes('constructor') ||
        message.includes('character limit') ||
        message.includes('scalar text limit') ||
        message.includes('live object handle limit') ||
        message.includes('element limit'),
      `${label}: ${message}`
    );
    return message;
  }
  throw new Error(`${label} should be rejected`);
}

async function expectRejectedAsync(label, action) {
  try {
    await action();
  } catch (error) {
    const message = String(error && error.message ? error.message : error);
    assert(message.includes('not allowed') || message.includes('disabled'), `${label}: ${message}`);
    return message;
  }
  throw new Error(`${label} should be rejected`);
}

exports.run = async function run() {
  assert(typeof Java === 'object', 'Java global is missing');
  assert(typeof Kotlin === 'object', 'Kotlin global is missing');

  const StringBuilder = Java.type('java.lang.StringBuilder');
  const text = new StringBuilder().append('restricted').append('-bridge').toString();
  assert(text === 'restricted-bridge', `safe StringBuilder failed: ${text}`);
  const DottedStringBuilder = Java['java.lang.StringBuilder'];
  assert(
    new DottedStringBuilder().append('dotted').toString() === 'dotted',
    'safe dotted StringBuilder lookup failed'
  );
  const PackagedStringBuilder = Java.package('java.lang.StringBuilder');
  assert(
    new PackagedStringBuilder().append('package').toString() === 'package',
    'safe package StringBuilder lookup failed'
  );
  assert(Java.java.lang.Integer.parseInt('42') === 42, 'safe Integer call failed');

  const blockedClasses = [
    'java.lang.Runtime',
    'java.lang.ProcessBuilder',
    'java.io.File',
    'java.util.Comparator',
    'java.util.Locale',
    'java.lang.CharSequence',
    'java.math.BigInteger',
    'java.math.BigDecimal',
    'android.content.Context',
    'com.ai.assistance.operit.core.application.ActivityLifecycleManager'
  ];
  blockedClasses.forEach((className) => {
    assert(Java.classExists(className) === false, `${className} should not exist through the bridge`);
  });

  const rejectionMessages = [];
  rejectionMessages.push(
    expectRejected('Runtime type lookup', () => Java.type('java.lang.Runtime'))
  );
  rejectionMessages.push(
    expectRejected('Runtime use lookup', () => Java.use('java.lang.Runtime'))
  );
  rejectionMessages.push(
    expectRejected('Runtime import lookup', () => Java.importClass('java.lang.Runtime'))
  );
  rejectionMessages.push(
    expectRejected('Runtime package-chain lookup', () => Java.java.lang.Runtime)
  );
  rejectionMessages.push(
    expectRejected('Runtime dotted-property lookup', () => Java['java.lang.Runtime'])
  );
  rejectionMessages.push(
    expectRejected('Runtime explicit-package lookup', () => Java.package('java.lang.Runtime'))
  );
  rejectionMessages.push(
    expectRejected('Runtime.getRuntime', () => Java.java.lang.Runtime.getRuntime())
  );
  rejectionMessages.push(
    expectRejected('application Context', () => Java.getApplicationContext())
  );
  rejectionMessages.push(
    expectRejected('external jar', () => Java.loadJar('/sdcard/Download/untrusted.jar'))
  );
  rejectionMessages.push(
    expectRejected('capacity StringBuilder constructor', () => new StringBuilder(2147483647))
  );
  rejectionMessages.push(
    expectRejected('capacity ArrayList constructor', () => new Java.java.util.ArrayList(2147483647))
  );
  rejectionMessages.push(
    expectRejected('String.repeat member', () => new Java.java.lang.String('x').repeat(2147483647))
  );
  rejectionMessages.push(
    expectRejected('CharSequence proxy', () =>
      new StringBuilder(Java.implement('java.lang.CharSequence', {
        length() { return 0; },
        charAt() { return 'x'; },
        subSequence() { return ''; }
      }))
    )
  );
  rejectionMessages.push(
    expectRejected('unannotated NativeInterface lifecycle method', () =>
      NativeInterface.detachJavaBridgeLifecycle()
    )
  );

  const list = new Java.java.util.ArrayList();
  list.add('b');
  list.add('a');
  rejectionMessages.push(
    expectRejected('quadratic collection helper', () =>
      Java.java.util.Collections.indexOfSubList(list, list)
    )
  );
  rejectionMessages.push(
    expectRejected('inferred Comparator proxy', () => {
      Java.java.util.Collections.sort(list, (left, right) => String(left).localeCompare(String(right)));
    })
  );
  rejectionMessages.push(
    await expectRejectedAsync('blocked suspend class', () =>
      Java.callSuspend('java.lang.Runtime', 'getRuntime')
    )
  );
  const cyclic = new Java.java.util.ArrayList();
  cyclic.add(cyclic);
  rejectionMessages.push(
    expectRejected('cyclic collection result', () => cyclic.toArray())
  );
  rejectionMessages.push(
    expectRejected('oversized lazy collection result', () =>
      Java.java.util.Collections.nCopies(2147483647, 'x')
    )
  );
  rejectionMessages.push(
    expectRejected('repeated scalar output', () =>
      Java.java.util.Collections.nCopies(64, 'x'.repeat(32768))
    )
  );
  assert(Java.java.lang.Integer.parseInt('7') === 7, 'bridge did not recover after rejection');

  const heldHandles = [];
  rejectionMessages.push(
    expectRejected('live object handle limit', () => {
      for (let index = 0; index < 1100; index += 1) {
        heldHandles.push(new StringBuilder());
      }
    })
  );

  return {
    success: true,
    allowed: ['java.lang.StringBuilder', 'java.lang.Integer', 'java.util.ArrayList'],
    blocked: blockedClasses,
    rejectionMessages
  };
};
