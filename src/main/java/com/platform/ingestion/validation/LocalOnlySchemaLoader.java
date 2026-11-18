package com.platform.ingestion.validation;

import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.resource.SchemaLoader;

/**
 * Refuses to load any schema resource by IRI. Contracts are self-contained (only "#/..." references are
 * accepted at upload), so a resolver attempting a remote or file load indicates tampering: fail closed.
 */
public final class LocalOnlySchemaLoader implements SchemaLoader {

    @Override
    public InputStreamSource getSchema(AbsoluteIri iri) {
        throw new SecurityException("Loading external schema resources is disabled: " + iri);
    }
}
