import test from 'node:test';
import assert from 'node:assert/strict';
import { projectBlueData, projectProcess } from '@blue-labs/myos-sdk';
test('native scalar envelopes are projected while structure and exact type identity survive', () => {
  const source = {type: {blueId: 'pi'}, name: 'Payment', activation: {active: {type: {blueId: 'boolean'}, value: false}}, paymentTerms: {amountMinor: {type: {blueId: 'integer'}, value: 200}, currency: {type: {blueId: 'text'}, value: 'USD'}}, payment: {completed: {type: {blueId: 'integer'}, value: 0}}, ref: {blueId: 'exact'}};
  const result = projectBlueData(source);
  assert.equal(result.activation.active, false); assert.equal(result.paymentTerms.amountMinor, 200);
  assert.deepEqual(result.ref, source.ref); assert.deepEqual(result.type, source.type);
  assert.notEqual(result,source); assert.equal(source.activation.active.value,false);
  const view=projectProcess(source, {format:'independent-process-view/1',roots:{pi:'payment'},clauses:{}});
  assert.equal(view.facts.find(f=>f.path==='/payment/completed').value,0);
});
test('projection preserves exact integers and does not discard structured value properties', () => {
  assert.equal(projectBlueData({type:{blueId:'integer'},value:9007199254740993n}),9007199254740993n);
  assert.deepEqual(projectBlueData({type:{blueId:'custom'},value:3,unit:'m'}),{type:{blueId:'custom'},value:3,unit:'m'});
  assert.deepEqual(projectBlueData({type:{blueId:'list'},items:[{value:false},{value:''},{value:0}]}),[false,'',0]);
});

test('schema-annotated native values project without discarding business fields', () => {
  const source = { type: { blueId: 'list' }, itemType: { blueId: 'reader-key' }, schema: { required: true },
    items: [{ type: { blueId: 'text' }, schema: { required: true }, value: 'reader-a' }] };
  assert.deepEqual(projectBlueData(source), ['reader-a']);
  assert.equal(source.items[0].value, 'reader-a');
  assert.equal(projectBlueData({ type: { blueId: 'integer' }, schema: { required: true }, value: 200 }), 200);
  assert.deepEqual(projectBlueData({ ...source, purpose: 'shipping' }), { ...source, items: ['reader-a'], purpose: 'shipping' });
});
