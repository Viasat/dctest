const fs = require('fs');
const Ajv = require('ajv');
const yaml = require('js-yaml');

const ajv = new Ajv();

// CLI args

const [dataFilePath, schemaFilePath] = process.argv.slice(2);

if (!dataFilePath || !schemaFilePath) {
  console.error('Usage: node validate.js <data.json> <schema.yaml>');
  process.exit(1);
}

// Read files

const data = JSON.parse(fs.readFileSync(dataFilePath, 'utf-8'));
const schema = yaml.load(fs.readFileSync(schemaFilePath, 'utf-8'));

// Validate

const validate = ajv.compile(schema);
const valid = validate(data);

if (valid) {
  console.log(`JSON in ${dataFilePath} conforms to spec ${schemaFilePath}`);
} else {
  console.error(`JSON in ${dataFilePath} does NOT conform to spec ${schemaFilePath}:`);
  console.error(validate.errors);
  process.exit(1);
}
