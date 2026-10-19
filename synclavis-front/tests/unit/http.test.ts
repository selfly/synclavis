import { describe, expect, it } from 'vitest';
import { ApiRequestError } from '@/api/http';

describe('api/http', () => {
  it('ApiRequestError 携带 code / message / details', () => {
    const error = new ApiRequestError('MERGE_CONFLICT', '合并存在冲突', { files: ['a.txt'] });
    expect(error).toBeInstanceOf(Error);
    expect(error.code).toBe('MERGE_CONFLICT');
    expect(error.message).toBe('合并存在冲突');
    expect(error.details).toEqual({ files: ['a.txt'] });
  });
});
