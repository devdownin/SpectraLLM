import { describe, expect, it } from 'vitest';
import { describeStatus, modelPhase } from './uiStatus';

describe('shared status semantics', () => {
  it.each(['PROCESSING', 'TRAINING', 'EXPORTING_DATASET', 'IMPORTING_MODEL', 'DOWNLOADING', 'REGISTERING', 'UPLOADING', 'running'])('uses the running label and tone for %s', status => {
    expect(describeStatus(status)).toEqual({ tone: 'secondary', labelKey: 'ui.status.task.running' });
  });
  it.each([['COMPLETED','success'],['FAILED','error'],['CANCELLED','neutral'],['partial','warning'],['pending','neutral']])('keeps %s distinct', (status, tone) => {
    expect(describeStatus(status).tone).toBe(tone);
    expect(describeStatus(status).labelKey).toBe(`ui.status.task.${status.toLowerCase()}`);
  });
  it('never presents an unknown state as success or pending', () => {
    expect(describeStatus('FUTURE_STATE')).toEqual({ tone: 'neutral', labelKey: null });
    expect(describeStatus(undefined)).toEqual({ tone: 'neutral', labelKey: null });
  });
  it('keeps document and model vocabulary separate', () => {
    expect(describeStatus('TRAINED','document')).toEqual({tone:'success',labelKey:'ui.status.document.TRAINED'});
    expect(describeStatus('configured','model')).toEqual({tone:'neutral',labelKey:'ui.status.model.configured'});
    expect(describeStatus('loading','model').tone).toBe('warning');
    expect(describeStatus('loaded','model').tone).toBe('success');
  });
  it.each([
    [undefined,'unverified'],[{},'unverified'],[{available:false},'unavailable'],
    [{available:true},'unverified'],[{available:true,details:{activeModelLoaded:false}},'loading'],
    [{available:true,details:{activeModelLoaded:true}},'unverified'],
    [{available:true,details:{activeModelLoaded:true,activeModel:'Qwen'}},'loaded'],
  ])('only confirms a loaded model with positive runtime evidence', (service, phase) => {
    expect(modelPhase(service)).toBe(phase);
  });
});
