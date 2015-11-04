package edu.illinois.library.cantaloupe.resource.iiif.v2_0;

import java.awt.Dimension;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.illinois.library.cantaloupe.Feature;
import edu.illinois.library.cantaloupe.ImageServerApplication;
import edu.illinois.library.cantaloupe.cache.Cache;
import edu.illinois.library.cantaloupe.cache.CacheFactory;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.image.OutputFormat;
import edu.illinois.library.cantaloupe.image.Quality;
import edu.illinois.library.cantaloupe.image.SourceFormat;
import edu.illinois.library.cantaloupe.processor.FileProcessor;
import edu.illinois.library.cantaloupe.processor.Processor;
import edu.illinois.library.cantaloupe.processor.ProcessorFactory;
import edu.illinois.library.cantaloupe.processor.ProcessorFeature;
import edu.illinois.library.cantaloupe.processor.StreamProcessor;
import edu.illinois.library.cantaloupe.processor.UnsupportedSourceFormatException;
import edu.illinois.library.cantaloupe.resolver.FileResolver;
import edu.illinois.library.cantaloupe.resolver.Resolver;
import edu.illinois.library.cantaloupe.resolver.ResolverFactory;
import edu.illinois.library.cantaloupe.resolver.StreamResolver;
import edu.illinois.library.cantaloupe.resource.iiif.AbstractInformationResource;
import org.restlet.data.MediaType;
import org.restlet.data.Preference;
import org.restlet.data.Reference;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;

/**
 * Handles IIIF information requests.
 *
 * @see <a href="http://iiif.io/api/image/2.0/#information-request">Information
 * Requests</a>
 */
public class InformationResource extends AbstractInformationResource {

    private static final Set<ServiceFeature> SUPPORTED_SERVICE_FEATURES =
            new HashSet<>();

    static {
        SUPPORTED_SERVICE_FEATURES.add(ServiceFeature.SIZE_BY_WHITELISTED);
        SUPPORTED_SERVICE_FEATURES.add(ServiceFeature.BASE_URI_REDIRECT);
        SUPPORTED_SERVICE_FEATURES.add(ServiceFeature.CANONICAL_LINK_HEADER);
        SUPPORTED_SERVICE_FEATURES.add(ServiceFeature.CORS);
        SUPPORTED_SERVICE_FEATURES.add(ServiceFeature.JSON_LD_MEDIA_TYPE);
    }

    /**
     * Responds to IIIF Information requests.
     *
     * @return StringRepresentation
     * @throws Exception
     */
    @Get("json")
    public StringRepresentation doGet() throws Exception {
        Map<String,Object> attrs = this.getRequest().getAttributes();
        edu.illinois.library.cantaloupe.request.iiif.v2_0.Identifier iiifIdentifier =
                edu.illinois.library.cantaloupe.request.iiif.v2_0.Identifier.
                        fromUri((String) attrs.get("identifier"));
        Identifier identifier = new Identifier(iiifIdentifier.toString());
        // Get the resolver
        Resolver resolver = ResolverFactory.getResolver();
        // Determine the format of the source image
        SourceFormat sourceFormat = resolver.getSourceFormat(identifier);
        if (sourceFormat.equals(SourceFormat.UNKNOWN)) {
            throw new UnsupportedSourceFormatException();
        }
        // Obtain an instance of the processor assigned to that format in
        // the config file
        Processor proc = ProcessorFactory.getProcessor(sourceFormat);
        // Get an ImageInfo instance corresponding to the source image
        ImageInfo imageInfo = getImageInfo(identifier,
                getSize(identifier, proc, resolver, sourceFormat),
                proc.getSupportedQualities(sourceFormat),
                proc.getSupportedFeatures(sourceFormat),
                proc.getAvailableOutputFormats(sourceFormat));
        // Transform the ImageInfo into JSON
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writer().
                without(SerializationFeature.WRITE_NULL_MAP_VALUES).
                without(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS).
                writeValueAsString(imageInfo);
        // could use JacksonRepresentation here; not sure whether it's worth bothering
        StringRepresentation rep = new StringRepresentation(json);

        this.addHeader("Link", "<http://iiif.io/api/image/2/context.json>; " +
                "rel=\"http://www.w3.org/ns/json-ld#context\"; type=\"application/ld+json\"");

        // 7. If the client has requested JSON-LD, set the content type to
        // that; otherwise set it to JSON
        List<Preference<MediaType>> preferences = this.getRequest().
                getClientInfo().getAcceptedMediaTypes();
        if (preferences.get(0) != null && preferences.get(0).toString().
                startsWith("application/ld+json")) {
            rep.setMediaType(new MediaType("application/ld+json"));
        } else {
            rep.setMediaType(new MediaType("application/json"));
        }
        return rep;
    }

    private ImageInfo getImageInfo(Identifier identifier, Dimension fullSize,
                                   Set<Quality> qualities,
                                   Set<ProcessorFeature> processorFeatures,
                                   Set<OutputFormat> outputFormats) {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setId(getImageUri(identifier));
        imageInfo.setWidth(fullSize.width);
        imageInfo.setHeight(fullSize.height);

        final String complianceUri = ComplianceLevel.getLevel(
                SUPPORTED_SERVICE_FEATURES, processorFeatures, qualities,
                outputFormats).getUri();
        imageInfo.getProfile().add(complianceUri);

        // sizes
        final short maxReductionFactor = 4;
        final short minSize = 256;
        for (double i = 2; i <= Math.pow(2, maxReductionFactor); i *= 2) {
            final int width = (int) Math.round(fullSize.width / i);
            final int height = (int) Math.round(fullSize.height / i);
            if (width < minSize || height < minSize) {
                break;
            }
            ImageInfo.Size size = new ImageInfo.Size(width, height);
            imageInfo.getSizes().add(0, size);
        }

        // formats
        Map<String, Set<String>> profileMap = new HashMap<>();
        Set<String> formatStrings = new HashSet<>();
        for (OutputFormat format : outputFormats) {
            formatStrings.add(format.getExtension());
        }
        profileMap.put("formats", formatStrings);
        imageInfo.getProfile().add(profileMap);

        // qualities
        Set<String> qualityStrings = new HashSet<>();
        for (Quality quality : qualities) {
            qualityStrings.add(quality.toString().toLowerCase());
        }
        profileMap.put("qualities", qualityStrings);

        // supports
        Set<String> featureStrings = new HashSet<>();
        for (Feature feature : processorFeatures) {
            featureStrings.add(feature.getName());
        }
        for (Feature feature : SUPPORTED_SERVICE_FEATURES) {
            featureStrings.add(feature.getName());
        }
        profileMap.put("supports", featureStrings);

        return imageInfo;
    }

    private String getImageUri(Identifier identifier) {
        return getPublicRootRef() + ImageServerApplication.IIIF_PATH +
                "/" + Reference.encode(identifier.toString());
    }

    /**
     * Gets the size of the image corresponding to the given identifier, first
     * by checking the cache and then, if necessary, by reading it from the
     * image and caching the result.
     *
     * @param identifier
     * @param proc
     * @param resolver
     * @param sourceFormat
     * @return
     * @throws Exception
     */
    private Dimension getSize(Identifier identifier, Processor proc,
                              Resolver resolver, SourceFormat sourceFormat)
            throws Exception {
        Dimension size = null;
        Cache cache = CacheFactory.getInstance();
        if (cache != null) {
            size = cache.getDimension(identifier);
            if (size == null) {
                size = readSize(identifier, resolver, proc, sourceFormat);
                cache.putDimension(identifier, size);
            }
        }
        if (size == null) {
            size = readSize(identifier, resolver, proc, sourceFormat);
        }
        return size;
    }

    /**
     * Reads the size from the source image.
     *
     * @param identifier
     * @param resolver
     * @param proc
     * @param sourceFormat
     * @return
     * @throws Exception
     */
    private Dimension readSize(Identifier identifier, Resolver resolver,
                               Processor proc, SourceFormat sourceFormat)
            throws Exception {
        Dimension size = null;
        if (resolver instanceof FileResolver) {
            if (proc instanceof FileProcessor) {
                size = ((FileProcessor)proc).getSize(
                        ((FileResolver) resolver).getFile(identifier),
                        sourceFormat);
            } else if (proc instanceof StreamProcessor) {
                size = ((StreamProcessor)proc).getSize(
                        ((StreamResolver) resolver).getInputStream(identifier),
                        sourceFormat);
            }
        } else if (resolver instanceof StreamResolver) {
            if (!(proc instanceof StreamProcessor)) {
                // StreamResolvers don't support FileProcessors
            } else {
                size = ((StreamProcessor)proc).getSize(
                        ((StreamResolver) resolver).getInputStream(identifier),
                        sourceFormat);
            }
        }
        return size;
    }

}
