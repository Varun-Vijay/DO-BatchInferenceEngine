package com.digitalocean.batchinference.inference;

/**
 * Translates between the domain request/response and the wire format of the external
 * inference endpoint, keeping vendor payload shapes out of the client and scheduler.
 */
public interface InferencePayloadMapper {

    Object toWirePayload(InferenceRequest request);

    InferenceResponse fromWirePayload(String responseBody);
}
