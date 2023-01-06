package com.softimad.amplify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.geojson.geom.GeometryJSON;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;



import org.opengis.referencing.operation.TransformException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Stack;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Controller
public class FileUploadController {

    //Save the uploaded file to this folder
    private static String UPLOADED_FOLDER = System.getProperty("user.dir")+"/src/main/resources/uploads/";

    @GetMapping("/")
    public String index() {

        return "uploadForm";
    }

    @PostMapping("/upload") // //new annotation since 4.3
    public ResponseEntity<String> singleFileUpload(HttpServletRequest request,
            @RequestParam("site") MultipartFile file,
                                   RedirectAttributes redirectAttributes) throws IOException, FactoryException, TransformException, ParseException {


        HttpHeaders httpHeaders= new HttpHeaders();

        String destDirectory = "";
        if (file.isEmpty()) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<String>("{data:{}}", httpHeaders, HttpStatus.BAD_REQUEST);
        }
        System.out.println("FILE SEPARATOR "+File.separator);
        Stack<String> allShapefiles = new Stack<String>();
        String filepath = UPLOADED_FOLDER + file.getOriginalFilename();
        try{
            byte[] bytes = file.getBytes();

            Path path = Paths.get(filepath);
            Files.write(path, bytes);

            destDirectory = UPLOADED_FOLDER+file.getOriginalFilename().split("\\.(?=[^\\.]+$)")[0];
            String zipFilePath = UPLOADED_FOLDER + file.getOriginalFilename();


            File directory = new File(destDirectory);
            // create output directory if it doesn't exist
            if(!directory.exists()) directory.mkdirs();

            File destDir = new File(destDirectory);
            if (!destDir.exists()) {
                destDir.mkdir();
            }
            ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
            ZipEntry entry = zipIn.getNextEntry();

            // iterates over entries in the zip file

            while (entry != null) {
                String filePath = destDirectory + File.separator + entry.getName();
                if (!entry.isDirectory()) {
                    // if the entry is a file, extracts it
                    String ext = FilenameUtils.getExtension(entry.getName());
                    Integer dot = StringUtils.countMatches(entry.getName(), ".");

                    if(dot == 1 && ext.trim().equals("shp")){

                        allShapefiles.push(filePath);
                    }

                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
                    byte[] bytesIn = new byte[4096];
                    int read = 0;
                    while ((read = zipIn.read(bytesIn)) != -1) {
                        bos.write(bytesIn, 0, read);
                    }
                    bos.close();
                } else {
                    // if the entry is a directory, make the directory
                    File dir = new File(filePath);
                    dir.mkdir();
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
            zipIn.close();
        }catch(IOException e){
            e.printStackTrace();
        }

        Iterator<String> iterator = allShapefiles.iterator();
        String url = "https://"+request.getLocalName()+"/zip/"+file.getOriginalFilename();
        String res = "{\"url\":\""+url+"\",\"data\" : [";
        while(iterator.hasNext()){
            File shapefile = new File(iterator.next());
            res += convertToJson(shapefile);
            if(iterator.hasNext()){
                res+=",";
            }
        }

        res += "]}";

        httpHeaders.set("Access-Control-Allow-Origin", "*");
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<String>(res, httpHeaders, HttpStatus.OK);


    }

    @GetMapping("/uploadStatus")
    public String uploadStatus() {
        return "uploadStatus";
    }

    public String convertToJson(File file) throws IOException, FactoryException, TransformException, ParseException {
        if (file == null) {
            return "{}";
        }
        ShapefileDataStore store = new ShapefileDataStore(file.toURI().toURL());

        SimpleFeatureSource source = store.getFeatureSource();
        SimpleFeatureCollection featureCollection = source.getFeatures();
        FeatureJSON fjson = new FeatureJSON();
        String geoJson = "{}";
        try (StringWriter writer = new StringWriter()) {
            fjson.writeFeatureCollection(featureCollection, writer);

            geoJson = writer.toString();
        }
        JSONParser parser = new JSONParser();
        JSONObject json = (JSONObject) parser.parse(geoJson);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readValue(geoJson, JsonNode.class);

        System.out.println("node.get(\"crs\") "+ node.get("crs"));
        if(node.get("crs") == null){
            return "{}";
        }
        JsonNode crs = node.get("crs").get("properties").get("name");
        CoordinateReferenceSystem sourceCRS = CRS.decode(crs.asText());

        CRSAuthorityFactory factory = CRS.getAuthorityFactory(true);
        CoordinateReferenceSystem targetCRS = factory.createCoordinateReferenceSystem("EPSG:4326");

//        CoordinateReferenceSystem targetCRS = CRS.decode("urn:ogc:def:crs:EPSG:6.6:4326");
        MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS);
        SimpleFeatureIterator i = featureCollection.features();

        String result = "";

        while(i.hasNext()) {
            SimpleFeature feature = i.next();
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            Geometry geometry2 = JTS.transform(geometry, transform);
            String g = new GeometryJSON(15).toString(geometry2);

            JSONObject res = new JSONObject();
            res.put("name",source.getName().toString().toUpperCase() );
            res.put("secteur",source.getName().toString().toUpperCase() );
            JSONObject geoJsonTemp =new JSONObject();
            JSONObject geoJsonTemp2 = new JSONObject();
            geoJsonTemp2.put("type", "Feature");
            geoJsonTemp2.put("geometry",(JSONObject) parser.parse(g));

            geoJsonTemp.put("data", geoJsonTemp2);
            geoJsonTemp.put("type", "geojson");

            res.put("shp",geoJsonTemp );

            result +=res.toJSONString();
            if(i.hasNext()){
                result += ",";
            }
        }
        return result;
    }

    @RequestMapping("/zip/{fileName:.+}")
    public void downloadPDFResource( HttpServletRequest request,
                                     HttpServletResponse response,
                                     @PathVariable("fileName") String fileName)
    {
        //If user is not authorized - he should be thrown out from here itself
        //Authorized user will download the file
        Path file = Paths.get(UPLOADED_FOLDER, fileName);
        if (Files.exists(file))
        {
            response.setContentType("application/zip");
            response.addHeader("Content-Disposition", "attachment; filename="+fileName);
            try
            {
                Files.copy(file, response.getOutputStream());
                response.getOutputStream().flush();
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    public static double truncate(double x, int precision) {
        double t = Math.pow(10, precision);
        return Math.round(x * t) / t;
    }




}
