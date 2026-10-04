import request from '../utils/request';

const AI_BASE_URL = (import.meta.env.VITE_AI_API_BASE_URL || '').replace(/\/$/, '');

export interface DocumentReference {
  fileId: number;
  fileName: string;
}

export interface KnowledgeFile {
  fileId: number;
  fileName: string;
  sizeBytes: number;
  status: string;
  errorMessage?: string | null;
  createTime: string | null;
}

export const listDocumentsAPI = (): Promise<KnowledgeFile[]> =>
  request.get('/documents', { baseURL: AI_BASE_URL });

export const uploadDocumentAPI = (file: File): Promise<KnowledgeFile> => {
  const body = new FormData();
  body.append('file', file);
  return request.post('/documents', body, {
    baseURL: AI_BASE_URL,
    timeout: 180000,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
};

export const parseDocumentAPI = (fileId: number): Promise<KnowledgeFile> =>
  request.post(`/documents/${fileId}/parse`, undefined, {
    baseURL: AI_BASE_URL,
    timeout: 180000
  });

export const reparseDocumentAPI = (fileId: number): Promise<KnowledgeFile> =>
  request.post(`/documents/${fileId}/reparse`, undefined, {
    baseURL: AI_BASE_URL,
    timeout: 180000
  });

export const deleteDocumentParseAPI = (fileId: number): Promise<KnowledgeFile> =>
  request.delete(`/documents/${fileId}/parse`, { baseURL: AI_BASE_URL });

export const deleteDocumentSourceAPI = (fileId: number): Promise<void> =>
  request.delete(`/documents/${fileId}`, { baseURL: AI_BASE_URL });
