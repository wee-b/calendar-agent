"""Explicit destructive integration test for rebuilding the configured RAG collection.

Run only with RUN_RAG_QDRANT_RESET_TEST set to the collection name.
This module is intentionally excluded from unittest's test_*.py discovery.
"""

import os
import unittest

from app.cache.embedding_cache import CachedEmbedding
from app.core.config.qdrant import get_qdrant_settings
from app.repository.qdrant import QdrantRepository
from app.service.rag_corpus import (
    DEFAULT_CORPUS_DIR, _java_name_uuid, deduplicate, import_corpus, load_corpus,
)


class RagCorpusImportIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def test_reset_collection_and_import_rag_data(self):
        collection = get_qdrant_settings().qdrant_collection
        if os.getenv("RUN_RAG_QDRANT_RESET_TEST") != collection:
            self.skipTest(f"设置 RUN_RAG_QDRANT_RESET_TEST={collection} 后才能清空集合")

        expected = deduplicate(load_corpus(DEFAULT_CORPUS_DIR))
        self.assertTrue(expected, "rag_data 中没有可导入的语料")
        repository = QdrantRepository()
        embedding = CachedEmbedding()

        # Check both external services before deleting the existing collection.
        existing = await repository.collection_info()
        await embedding.embed(expected[0].text)
        if existing is not None:
            await repository.delete_collection()
        self.assertIsNone(await repository.collection_info(), "集合清空后仍存在")

        imported = await import_corpus(DEFAULT_CORPUS_DIR, repository=repository,
                                       embedding=embedding)
        actual = await repository.scroll_all()
        self.assertEqual(len(expected), imported)
        self.assertEqual(imported, len(actual))

        expected_by_id = {
            _java_name_uuid("\0".join((chunk.source, chunk.section, chunk.text))): chunk
            for chunk in expected
        }
        self.assertEqual(set(expected_by_id), {str(point.id) for point in actual})
        for point in actual:
            chunk = expected_by_id[str(point.id)]
            self.assertEqual({"source": chunk.source, "section": chunk.section,
                              "text": chunk.text, "char_count": len(chunk.text)},
                             point.payload.model_dump())
