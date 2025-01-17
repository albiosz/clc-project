package clc.project.knote;

import jakarta.annotation.PostConstruct;
import lombok.*;
import org.apache.commons.io.IOUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;

import io.minio.MinioClient;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class KnoteApplication {
	public static void main(String[] args) {
		SpringApplication.run(KnoteApplication.class, args);
	}
}

@Document(collection = "notes")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class Note {
	@Id
	private String id;
	private String description;

	@Override
	public String toString() {
		return description;
	}
}

interface NotesRepository extends MongoRepository<Note, String> {
}

@Controller
@EnableConfigurationProperties(KnoteProperties.class)
class KNoteController {

	@Autowired
	private NotesRepository notesRepository;
	@Autowired
	private KnoteProperties properties;

	private Parser parser = Parser.builder().build();
	private HtmlRenderer renderer = HtmlRenderer.builder().build();

	private MinioClient minioClient;

	@PostConstruct
	public void init() throws InterruptedException {
		initMinio();
	}

	@GetMapping("/")
	public String index(Model model) {
		getAllNotes(model); // it sets the notes attributes, that is read by freemarker
		return "index";
	}

	private void getAllNotes(Model model) {
		List<Note> notes = notesRepository.findAll();
		Collections.reverse(notes); // show the last note first
		model.addAttribute("notes", notes);
	}

	@GetMapping(value = "/img/{name}", produces = MediaType.IMAGE_PNG_VALUE)
	public @ResponseBody byte[] getImageByName(@PathVariable String name) throws Exception {
		InputStream imageStream = minioClient.getObject(properties.getMinioBucket(), name);
		return IOUtils.toByteArray(imageStream);
	}

	@PostMapping("/note")
	public String saveNotes(@RequestParam("image") MultipartFile file,
													@RequestParam String description,
													@RequestParam(required = false) String publish,
													@RequestParam(required = false) String upload,
													Model model) throws Exception {
		if (publish != null && publish.equals("Publish")) {
			saveNote(markupToHtml(description), model);
			cleanTextarea(model);
			getAllNotes(model);
			return "redirect:/";
		}
		if (upload != null && upload.equals("Upload")) {
			if (file != null && file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty()) {
				uploadImage(file, description, model);
			}
			getAllNotes(model);
			return "index";
		}

		return "index";
	}

	private void saveNote(String description, Model model) {
		if (description == null || description.trim().isEmpty()) {
			return;
		}
		notesRepository.save(new Note(null, description.trim()));

	}

	private static void cleanTextarea(Model model) {
		model.addAttribute("description", "");
	}

	private String markupToHtml(String markup) {
		Node document = parser.parse(markup.trim());
		return renderer.render(document);
	}

	private void uploadImage(MultipartFile file, String description, Model model) throws Exception {
		String fileId = UUID.randomUUID() + "."
				+ file.getOriginalFilename().split("\\.")[1];
		minioClient.putObject(properties.getMinioBucket(), fileId, file.getInputStream(),
				file.getSize(), null, null, file.getContentType());
		model.addAttribute("description", description + " ![](/img/" + fileId + ")");
	}

	private void initMinio() throws InterruptedException {
		boolean success = false;
		while (!success) {
			try {
				minioClient = new MinioClient("http://" + properties.getMinioHost() + ":9000" ,
						properties.getMinioAccessKey(),
						properties.getMinioSecretKey(),
						false);
				// Check if the bucket already exists.
				boolean isExist = minioClient.bucketExists(properties.getMinioBucket());
				if (isExist) {
					System.out.println("> Bucket already exists.");
				} else {
					minioClient.makeBucket(properties.getMinioBucket());
				}
				success = true;
			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("> Minio Reconnect: " + properties.isMinioReconnectEnabled());
				if (properties.isMinioReconnectEnabled()) {
					try {
						Thread.sleep(5000);
					} catch (InterruptedException ex) {
						ex.printStackTrace();
					}
				} else {
					success = true;
				}
			}
		}
		System.out.println("> Minio initialized!");
	}
}

@ConfigurationProperties(prefix = "knote")
class KnoteProperties {

	@Value("${minio.host:}")
	private String minioHost;

	@Value("${minio.bucket:image-storage}")
	private String minioBucket;

	@Value("${minio.access.key:}")
	private String minioAccessKey;

	@Value("${minio.secret.key:}")
	private String minioSecretKey;

	@Value("${minio.useSSL:false}")
	private boolean minioUseSSL;

	@Value("${minio.reconnect.enabled:true}")
	private boolean minioReconnectEnabled;

	public String getMinioHost() {
		return minioHost;
	}

	public String getMinioBucket() {
		return minioBucket;
	}

	public String getMinioAccessKey() {
		return minioAccessKey;
	}

	public String getMinioSecretKey() {
		return minioSecretKey;
	}

	public boolean isMinioUseSSL() {
		return minioUseSSL;
	}

	public boolean isMinioReconnectEnabled() {
		return minioReconnectEnabled;
	}
}