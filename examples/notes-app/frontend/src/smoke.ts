import { api } from './api';
import { call, notifyJava } from './jolt';

/** Exercises every request against the real Java backend, then reports ok/fail to Java (see NotesApp --smoke). */
export async function runSmoke(): Promise<void> {
  try {
    const before = (await api.list()).length;
    const added = await api.add('smoke note ✓');
    if ((await api.list()).length !== before + 1) throw new Error('add did not grow the list');
    await api.remove(added.id);
    if ((await api.list()).length !== before) throw new Error('delete did not shrink the list');
    await expectRejected(() => api.add('   '), 'an empty note should be rejected');
    await expectRejected(() => call('no.such.method'), 'an unknown method should be rejected');

    const dark = { preference: 'dark', scheme: 'dark', background: '#15110f', text: '#f4ede8' } as const;
    await api.setTheme(dark);
    if ((await api.getPreference()).preference !== 'dark') throw new Error('the theme was not remembered');
    await expectRejected(() => api.setTheme({ ...dark, background: 'red' }), 'a bad colour should be rejected');
    notifyJava({ smoke: 'ok' });
  } catch (e) {
    notifyJava({ smoke: 'fail', error: String(e) });
  }
}

async function expectRejected(action: () => Promise<unknown>, message: string): Promise<void> {
  try {
    await action();
  } catch {
    return;
  }
  throw new Error(message);
}
