(async () => {
  const out = {};
  try {
    out.greeting = await jolt.call('greet.hello', { name: 'Jolt', tags: ['a', 'b'] });
    try {
      await jolt.call('greet.refuse');
    } catch (e) {
      out.refused = e.code + ': ' + e.message;
    }
    out.origin = location.origin;
  } catch (e) {
    out.error = String((e && e.message) || e);
  }
  await jolt.call('test.report', out);
})();
